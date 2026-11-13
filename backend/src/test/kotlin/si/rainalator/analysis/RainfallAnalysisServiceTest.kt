package si.rainalator.analysis

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.withinPercentage
import org.assertj.core.data.Offset
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import si.rainalator.ingestion.ProjectionConverter
import si.rainalator.ingestion.RadarScan
import si.rainalator.ingestion.SrdHeader
import si.rainalator.storage.RasterStorageService
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.sql.DriverManager
import java.time.ZoneOffset
import java.time.ZonedDateTime
import javax.imageio.ImageIO

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RainfallAnalysisServiceTest {

    companion object {
        @Container
        @JvmStatic
        val postgis: PostgreSQLContainer<*> = PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:16-3.4")
                .asCompatibleSubstituteFor("postgres")
        )
            .withDatabaseName("rainalator_test")
            .withUsername("test")
            .withPassword("test")
    }

    private lateinit var analysisService: RainfallAnalysisService
    private lateinit var storageService: RasterStorageService
    private lateinit var dataSource: javax.sql.DataSource

    private val projectionConverter = ProjectionConverter()

    @BeforeAll
    fun setupSchema() {
        postgis.start()
        DriverManager.getConnection(postgis.jdbcUrl, postgis.username, postgis.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE EXTENSION IF NOT EXISTS postgis")
                stmt.execute("CREATE EXTENSION IF NOT EXISTS postgis_raster")
                stmt.execute("""
                    CREATE TABLE radar_scans (
                        scan_time     TIMESTAMPTZ NOT NULL PRIMARY KEY,
                        ingested_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        source_radars TEXT[],
                        raster_data   RASTER NOT NULL,
                        bbox          GEOMETRY(Polygon, 4326),
                        scan_metadata JSONB
                    )
                """)
                stmt.execute("CREATE INDEX idx_radar_scans_bbox ON radar_scans USING GIST (bbox)")
            }
        }
    }

    @AfterAll
    fun teardown() {
        postgis.stop()
    }

    @BeforeEach
    fun setUp() {
        val pgDs = org.postgresql.ds.PGSimpleDataSource()
        pgDs.setUrl(postgis.jdbcUrl)
        pgDs.user = postgis.username
        pgDs.password = postgis.password
        dataSource = pgDs

        storageService = RasterStorageService(dataSource)
        analysisService = RainfallAnalysisService(dataSource, storageService)

        dataSource.connection.use { c ->
            c.createStatement().execute("DELETE FROM radar_scans")
        }
    }

    private fun makeHeader(width: Int = 5, height: Int = 3): SrdHeader = SrdHeader(
        domain = "TEST",
        sourceRadars = listOf("T1"),
        time = ZonedDateTime.of(2026, 3, 30, 12, 0, 0, 0, ZoneOffset.UTC),
        width = width,
        height = height,
        cellSizeX = 1.0,
        cellSizeY = 1.0,
        projection = "LCC",
        ellipseA = 6371.0,
        ellipseB = 6371.0,
        par1 = 46.120,
        par2 = 46.120,
        originLon = 14.815,
        originLat = 46.120,
        shiftX = -2.0,
        shiftY = -1.0,
        offset = 64,
        start = -8.0,
        slope = 2.0,
        nodata = 126,
    )

    private fun makeScan(
        header: SrdHeader = makeHeader(),
        values: FloatArray = FloatArray(header.width * header.height) { 1.0f },
    ): RadarScan = RadarScan(header, values)

    private fun bboxPolygonWkt(header: SrdHeader): String {
        val (sw, ne) = projectionConverter.gridBounds(header)
        return "POLYGON((${sw.lon} ${sw.lat}, ${ne.lon} ${sw.lat}, ${ne.lon} ${ne.lat}, ${sw.lon} ${ne.lat}, ${sw.lon} ${sw.lat}))"
    }

    private val t0 = ZonedDateTime.of(2026, 3, 30, 12, 0, 0, 0, ZoneOffset.UTC)

    @Nested
    inner class AnalyzeRainfall {

        @Test
        fun `returns empty result when no scans in time range`() {
            val result = analysisService.analyzeRainfall(
                bboxPolygonWkt(makeHeader()),
                t0.minusHours(2),
                t0.minusHours(1),
            )
            assertThat(result.scans).isEmpty()
            assertThat(result.accumulatedRainfallMm).isEqualTo(0.0)
        }

        @Test
        fun `single scan with uniform values returns correct stats`() {
            storageService.insert(makeScan())
            val wkt = bboxPolygonWkt(makeHeader())

            val result = analysisService.analyzeRainfall(wkt, t0.minusMinutes(1), t0.plusMinutes(1))

            assertThat(result.scans).hasSize(1)
            val scan = result.scans[0]
            assertThat(scan.mean).isCloseTo(1.0, Offset.offset(0.01))
            assertThat(scan.count).isEqualTo(15) // 5x3
            assertThat(scan.sum).isCloseTo(15.0, Offset.offset(0.1))
        }

        @Test
        fun `accumulated rainfall for single scan is mean times 5 div 60`() {
            val values = FloatArray(15) { 2.0f }
            storageService.insert(makeScan(values = values))
            val wkt = bboxPolygonWkt(makeHeader())

            val result = analysisService.analyzeRainfall(wkt, t0.minusMinutes(1), t0.plusMinutes(1))

            // 2.0 mm/h * (5/60) h = 0.1667 mm
            assertThat(result.accumulatedRainfallMm).isCloseTo(2.0 * 5.0 / 60.0, Offset.offset(0.01))
        }

        @Test
        fun `multiple scans accumulate correctly`() {
            val header = makeHeader()
            val wkt = bboxPolygonWkt(header)

            // Insert 3 scans at 5-min intervals, all 6.0 mm/h
            for (i in 0..2) {
                val h = header.copy(time = t0.plusMinutes(i * 5L))
                storageService.insert(makeScan(header = h, values = FloatArray(15) { 6.0f }))
            }

            val result = analysisService.analyzeRainfall(wkt, t0.minusMinutes(1), t0.plusMinutes(11))

            assertThat(result.scans).hasSize(3)
            // 6.0 mm/h * 3 scans * (5/60) h = 1.5 mm
            assertThat(result.accumulatedRainfallMm).isCloseTo(1.5, Offset.offset(0.01))
        }

        @Test
        fun `polygon outside raster returns empty results`() {
            storageService.insert(makeScan())
            // Polygon in Antarctica — no overlap with Slovenia
            val wkt = "POLYGON((-70 -80, -60 -80, -60 -70, -70 -70, -70 -80))"

            val result = analysisService.analyzeRainfall(wkt, t0.minusMinutes(1), t0.plusMinutes(1))

            assertThat(result.scans).isEmpty()
            assertThat(result.accumulatedRainfallMm).isEqualTo(0.0)
        }

        @Test
        fun `nodata pixels are excluded from stats`() {
            // First row (5 cells) = NaN, rest = 2.0
            val values = FloatArray(15) { if (it < 5) Float.NaN else 2.0f }
            storageService.insert(makeScan(values = values))
            val wkt = bboxPolygonWkt(makeHeader())

            val result = analysisService.analyzeRainfall(wkt, t0.minusMinutes(1), t0.plusMinutes(1))

            assertThat(result.scans).hasSize(1)
            assertThat(result.scans[0].count).isEqualTo(10) // 15 - 5 NaN
            assertThat(result.scans[0].mean).isCloseTo(2.0, Offset.offset(0.01))
        }

        @Test
        fun `per-scan stats include min and max`() {
            // Values: first 5 = 1.0, next 5 = 3.0, last 5 = 5.0
            val values = FloatArray(15) { i ->
                when {
                    i < 5 -> 1.0f
                    i < 10 -> 3.0f
                    else -> 5.0f
                }
            }
            storageService.insert(makeScan(values = values))
            val wkt = bboxPolygonWkt(makeHeader())

            val result = analysisService.analyzeRainfall(wkt, t0.minusMinutes(1), t0.plusMinutes(1))

            assertThat(result.scans[0].min).isCloseTo(1.0, Offset.offset(0.01))
            assertThat(result.scans[0].max).isCloseTo(5.0, Offset.offset(0.01))
        }

        @Test
        fun `scans are ordered by time`() {
            val header = makeHeader()
            // Insert out of order
            storageService.insert(makeScan(header = header.copy(time = t0.plusMinutes(10))))
            storageService.insert(makeScan(header = header.copy(time = t0)))
            storageService.insert(makeScan(header = header.copy(time = t0.plusMinutes(5))))
            val wkt = bboxPolygonWkt(header)

            val result = analysisService.analyzeRainfall(wkt, t0.minusMinutes(1), t0.plusMinutes(11))

            assertThat(result.scans).hasSize(3)
            assertThat(result.scans[0].scanTime).isEqualTo(t0)
            assertThat(result.scans[1].scanTime).isEqualTo(t0.plusMinutes(5))
            assertThat(result.scans[2].scanTime).isEqualTo(t0.plusMinutes(10))
        }
    }

    @Nested
    inner class AreaAndVolume {

        private val bigHeader = makeHeader(width = 60, height = 40)

        private fun lonAt(f: Double): Double {
            val (sw, ne) = projectionConverter.gridBounds(bigHeader)
            return sw.lon + f * (ne.lon - sw.lon)
        }

        private fun latAt(f: Double): Double {
            val (sw, ne) = projectionConverter.gridBounds(bigHeader)
            return sw.lat + f * (ne.lat - sw.lat)
        }

        private fun rectWkt(lonMin: Double, latMin: Double, lonMax: Double, latMax: Double) =
            "POLYGON(($lonMin $latMin, $lonMax $latMin, $lonMax $latMax, $lonMin $latMax, $lonMin $latMin))"

        private fun insertUniform(rateMmPerHour: Float) {
            storageService.insert(
                RadarScan(bigHeader, FloatArray(bigHeader.width * bigHeader.height) { rateMmPerHour })
            )
        }

        @Test
        fun `areaKm2 reports the geodesic area of the selected polygon`() {
            insertUniform(2.0f)
            val wkt = rectWkt(lonAt(0.2), latAt(0.3), lonAt(0.4), latAt(0.7))

            val result = analysisService.analyzeRainfall(wkt, t0.minusMinutes(1), t0.plusMinutes(1))

            val expectedKm2 = dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT ST_Area(ST_GeomFromText(?, 4326)::geography) / 1e6").use { stmt ->
                    stmt.setString(1, wkt)
                    val rs = stmt.executeQuery()
                    rs.next()
                    rs.getDouble(1)
                }
            }
            assertThat(result.areaKm2).isCloseTo(expectedKm2, Offset.offset(0.01))
        }

        @Test
        fun `areaKm2 is reported even when no scans fall in the range`() {
            val wkt = rectWkt(lonAt(0.2), latAt(0.3), lonAt(0.4), latAt(0.7))

            val result = analysisService.analyzeRainfall(wkt, t0.minusHours(5), t0.minusHours(4))

            assertThat(result.scans).isEmpty()
            assertThat(result.totalVolumeM3).isEqualTo(0.0)
            assertThat(result.areaKm2).isGreaterThan(0.0)
        }

        @Test
        fun `doubling the area doubles the collected volume for a uniform field`() {
            insertUniform(4.0f)
            val small = rectWkt(lonAt(0.2), latAt(0.3), lonAt(0.4), latAt(0.7))
            val double = rectWkt(lonAt(0.2), latAt(0.3), lonAt(0.6), latAt(0.7))

            val smallResult = analysisService.analyzeRainfall(small, t0.minusMinutes(1), t0.plusMinutes(1))
            val doubleResult = analysisService.analyzeRainfall(double, t0.minusMinutes(1), t0.plusMinutes(1))

            assertThat(doubleResult.areaKm2 / smallResult.areaKm2).isCloseTo(2.0, Offset.offset(0.02))
            assertThat(doubleResult.totalVolumeM3 / smallResult.totalVolumeM3).isCloseTo(2.0, Offset.offset(0.05))
            // The depth metric, by contrast, must stay put — that is what makes it an average
            assertThat(doubleResult.accumulatedRainfallMm)
                .isCloseTo(smallResult.accumulatedRainfallMm, Offset.offset(0.001))
        }

        @Test
        fun `volume equals mean depth times area`() {
            insertUniform(6.0f)
            val wkt = rectWkt(lonAt(0.2), latAt(0.3), lonAt(0.6), latAt(0.7))

            val result = analysisService.analyzeRainfall(wkt, t0.minusMinutes(1), t0.plusMinutes(1))

            // 1 mm over 1 km² = 1000 m³
            val expected = result.accumulatedRainfallMm * result.areaKm2 * 1000.0
            assertThat(result.totalVolumeM3).isCloseTo(expected, withinPercentage(3.0))
        }

        @Test
        fun `volume accumulates across scans`() {
            val wkt = rectWkt(lonAt(0.2), latAt(0.3), lonAt(0.6), latAt(0.7))
            for (i in 0..2) {
                storageService.insert(
                    RadarScan(
                        bigHeader.copy(time = t0.plusMinutes(i * 5L)),
                        FloatArray(bigHeader.width * bigHeader.height) { 3.0f },
                    )
                )
            }

            val one = analysisService.analyzeRainfall(wkt, t0.minusMinutes(1), t0.plusMinutes(1))
            val three = analysisService.analyzeRainfall(wkt, t0.minusMinutes(1), t0.plusMinutes(11))

            assertThat(three.scans).hasSize(3)
            assertThat(three.totalVolumeM3).isCloseTo(3.0 * one.totalVolumeM3, withinPercentage(0.1))
        }

        @Test
        fun `nodata pixels contribute no volume`() {
            // Top half nodata, bottom half 4.0 mm/h
            val halfway = bigHeader.width * bigHeader.height / 2
            storageService.insert(
                RadarScan(
                    bigHeader,
                    FloatArray(bigHeader.width * bigHeader.height) { if (it < halfway) Float.NaN else 4.0f },
                )
            )
            val wholeGrid = rectWkt(lonAt(0.0), latAt(0.0), lonAt(1.0), latAt(1.0))

            val result = analysisService.analyzeRainfall(wholeGrid, t0.minusMinutes(1), t0.plusMinutes(1))

            // Only half the grid holds data, so volume is half of what a fully-covered grid would give
            val fullyCovered = 4.0 * (5.0 / 60.0) * result.areaKm2 * 1000.0
            assertThat(result.totalVolumeM3).isCloseTo(fullyCovered / 2.0, withinPercentage(3.0))
        }
    }

    @Nested
    inner class RenderOverlay {

        private val oneScan = t0 to t0.plusMinutes(5)

        private fun renderRate(start: ZonedDateTime = oneScan.first, end: ZonedDateTime = oneScan.second) =
            analysisService.renderOverlayPng(start, end, ColorScaleMode.RATE)

        @Test
        fun `returns null when the bucket holds no scan`() {
            val result = renderRate()
            assertThat(result).isNull()
        }

        @Test
        fun `returns PNG bytes for existing scan`() {
            storageService.insert(makeScan())

            val result = renderRate()

            assertThat(result).isNotNull()
            // PNG magic bytes: 0x89 0x50 0x4E 0x47
            assertThat(result!!.pngBytes[0]).isEqualTo(0x89.toByte())
            assertThat(result.pngBytes[1]).isEqualTo(0x50.toByte()) // 'P'
            assertThat(result.pngBytes[2]).isEqualTo(0x4E.toByte()) // 'N'
            assertThat(result.pngBytes[3]).isEqualTo(0x47.toByte()) // 'G'
        }

        @Test
        fun `PNG has correct dimensions`() {
            storageService.insert(makeScan())

            val result = renderRate()!!
            val image = ImageIO.read(result.pngBytes.inputStream())

            assertThat(image.width).isEqualTo(5)
            assertThat(image.height).isEqualTo(3)
        }

        @Test
        fun `transparent pixels for nodata values`() {
            // All NaN
            val values = FloatArray(15) { Float.NaN }
            storageService.insert(makeScan(values = values))

            val result = renderRate()!!
            val image = ImageIO.read(result.pngBytes.inputStream())

            // All pixels should be transparent
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val alpha = (image.getRGB(x, y) shr 24) and 0xFF
                    assertThat(alpha).isEqualTo(0)
                }
            }
        }

        @Test
        fun `non-zero rainfall has non-transparent color`() {
            val values = FloatArray(15) { 5.0f }
            storageService.insert(makeScan(values = values))

            val result = renderRate()!!
            val image = ImageIO.read(result.pngBytes.inputStream())

            // All pixels should be non-transparent for uniform 5.0 mm/h
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val alpha = (image.getRGB(x, y) shr 24) and 0xFF
                    assertThat(alpha).isGreaterThan(0)
                }
            }
        }

        @Test
        fun `geo bounds are returned correctly`() {
            storageService.insert(makeScan())
            val (sw, ne) = projectionConverter.gridBounds(makeHeader())

            val result = renderRate()!!

            assertThat(result.bounds.south).isCloseTo(sw.lat, Offset.offset(0.001))
            assertThat(result.bounds.west).isCloseTo(sw.lon, Offset.offset(0.001))
            assertThat(result.bounds.north).isCloseTo(ne.lat, Offset.offset(0.001))
            assertThat(result.bounds.east).isCloseTo(ne.lon, Offset.offset(0.001))
        }

        @Test
        fun `rate scale on a single-scan bucket paints exactly the per-scan image`() {
            // Values spread across several bands, none of them sitting on a band edge
            val values = FloatArray(15) { (it + 1) * 0.7f }
            storageService.insert(makeScan(values = values))

            val result = renderRate()!!

            val perScan = RainfallColorScale().overlayScale(ColorScaleMode.RATE, spanHours = 1.0)
            val expected = BufferedImage(5, 3, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 3) {
                for (x in 0 until 5) {
                    expected.setRGB(x, y, perScan.toArgb(values[y * 5 + x].toDouble()))
                }
            }
            val encoded = ByteArrayOutputStream().also { ImageIO.write(expected, "PNG", it) }.toByteArray()

            assertThat(result.pngBytes).isEqualTo(encoded)
        }

        @Test
        fun `a bucket paints the depth summed over its scans`() {
            for (i in 0..1) {
                storageService.insert(
                    makeScan(header = makeHeader().copy(time = t0.plusMinutes(i * 5L)), values = FloatArray(15) { 9.0f })
                )
            }

            val result = analysisService.renderOverlayPng(t0, t0.plusMinutes(10), ColorScaleMode.FIXED)!!
            val image = ImageIO.read(result.pngBytes.inputStream())

            // 2 scans × 9 mm/h × 5 min = 1.5 mm, which lands in the 1–2 mm band
            val fixed = RainfallColorScale().overlayScale(ColorScaleMode.FIXED, spanHours = 1.0 / 6)
            assertThat(image.getRGB(0, 0)).isEqualTo(fixed.toArgb(1.5))
            assertThat(image.getRGB(0, 0)).isNotEqualTo(fixed.toArgb(0.75))
        }

        @Test
        fun `scans on or after the bucket end are excluded`() {
            for (i in 0..1) {
                storageService.insert(
                    makeScan(header = makeHeader().copy(time = t0.plusMinutes(i * 5L)), values = FloatArray(15) { 9.0f })
                )
            }

            val result = analysisService.renderOverlayPng(t0, t0.plusMinutes(5), ColorScaleMode.FIXED)!!
            val image = ImageIO.read(result.pngBytes.inputStream())

            // Only the scan at t0: 9 mm/h × 5 min = 0.75 mm
            val fixed = RainfallColorScale().overlayScale(ColorScaleMode.FIXED, spanHours = 1.0 / 12)
            assertThat(image.getRGB(0, 0)).isEqualTo(fixed.toArgb(0.75))
        }

        @Test
        fun `autofit stretches the bands to the supplied maximum`() {
            for (i in 0..1) {
                storageService.insert(
                    makeScan(header = makeHeader().copy(time = t0.plusMinutes(i * 5L)), values = FloatArray(15) { 9.0f })
                )
            }

            val result = analysisService.renderOverlayPng(
                t0, t0.plusMinutes(10), ColorScaleMode.AUTOFIT, scaleMax = 3.0,
            )!!
            val image = ImageIO.read(result.pngBytes.inputStream())

            val autofit = RainfallColorScale().overlayScale(ColorScaleMode.AUTOFIT, spanHours = 1.0 / 6, scaleMax = 3.0)
            assertThat(image.getRGB(0, 0)).isEqualTo(autofit.toArgb(1.5))
        }

        @Test
        fun `the returned legend describes the painted scale`() {
            storageService.insert(makeScan())

            val rate = renderRate()!!
            val fixed = analysisService.renderOverlayPng(t0, t0.plusMinutes(5), ColorScaleMode.FIXED)!!

            assertThat(rate.legendUnit).isEqualTo("mm/h")
            assertThat(rate.legendThresholds).hasSize(14)
            assertThat(rate.legendColorsHex).hasSize(15)
            assertThat(rate.legendColorsHex.first()).isEqualTo("085AFE")
            assertThat(fixed.legendUnit).isEqualTo("mm")
            assertThat(fixed.legendThresholds.last()).isCloseTo(200.0, Offset.offset(1e-9))
        }

        @Test
        fun `rejects a bucket whose start is not before its end`() {
            assertThatThrownBy { analysisService.renderOverlayPng(t0, t0, ColorScaleMode.RATE) }
                .isInstanceOf(InvalidQueryException::class.java)
                .hasMessage("start must be before end")
        }

        @Test
        fun `rejects autofit without a positive scaleMax`() {
            assertThatThrownBy {
                analysisService.renderOverlayPng(t0, t0.plusMinutes(5), ColorScaleMode.AUTOFIT)
            }.isInstanceOf(InvalidQueryException::class.java)
                .hasMessage("scaleMax is required for autofit")

            assertThatThrownBy {
                analysisService.renderOverlayPng(t0, t0.plusMinutes(5), ColorScaleMode.AUTOFIT, scaleMax = 0.0)
            }.isInstanceOf(InvalidQueryException::class.java)
                .hasMessage("scaleMax is required for autofit")
        }
    }

    @Nested
    inner class BucketedAnalysis {

        private val header = makeHeader()
        private val wkt = bboxPolygonWkt(makeHeader())

        private fun insertScans(count: Int, from: ZonedDateTime = t0, rateMmPerHour: Float = 6.0f) {
            for (i in 0 until count) {
                storageService.insert(
                    makeScan(
                        header = header.copy(time = from.plusMinutes(i * 5L)),
                        values = FloatArray(15) { rateMmPerHour },
                    )
                )
            }
        }

        @Test
        fun `without bucketMinutes every scan is its own five-minute bucket`() {
            insertScans(3)

            val result = analysisService.analyzeRainfall(wkt, t0, t0.plusMinutes(15))

            assertThat(result.buckets).hasSize(3)
            assertThat(result.bucketMinutes).isEqualTo(5)
            assertThat(result.buckets[0].start).isEqualTo(t0)
            assertThat(result.buckets[0].end).isEqualTo(t0.plusMinutes(5))
            assertThat(result.buckets.map { it.scanCount }).containsOnly(1)
            assertThat(result.buckets.map { it.partial }).containsOnly(false)
        }

        @Test
        fun `a per-scan bucket reports the scan's own rate`() {
            insertScans(1, rateMmPerHour = 6.0f)

            val result = analysisService.analyzeRainfall(wkt, t0, t0.plusMinutes(5))

            assertThat(result.buckets).hasSize(1)
            assertThat(result.buckets[0].meanRateMmH).isCloseTo(6.0, Offset.offset(0.01))
            assertThat(result.buckets[0].depthMm).isCloseTo(0.5, Offset.offset(0.01))
        }

        @Test
        fun `bucketing regroups rows without changing the window totals`() {
            insertScans(12)
            val to = t0.plusMinutes(60)

            val perScan = analysisService.analyzeRainfall(wkt, t0, to)
            val bucketed = analysisService.analyzeRainfall(wkt, t0, to, bucketMinutes = 15)

            assertThat(bucketed.accumulatedRainfallMm).isCloseTo(perScan.accumulatedRainfallMm, Offset.offset(1e-9))
            assertThat(bucketed.totalVolumeM3).isCloseTo(perScan.totalVolumeM3, Offset.offset(1e-6))
            assertThat(bucketed.buckets.sumOf { it.depthMm })
                .isCloseTo(bucketed.accumulatedRainfallMm, Offset.offset(1e-9))
            assertThat(bucketed.buckets.sumOf { it.volumeM3 })
                .isCloseTo(bucketed.totalVolumeM3, Offset.offset(1e-6))
            assertThat(bucketed.buckets.sumOf { it.scanCount }).isEqualTo(12)
        }

        @Test
        fun `the oldest bucket is clamped to the window start and marked partial`() {
            insertScans(12)

            val result = analysisService.analyzeRainfall(wkt, t0, t0.plusMinutes(55), bucketMinutes = 15)

            assertThat(result.buckets).hasSize(4)
            assertThat(result.buckets.first().start).isEqualTo(t0)
            assertThat(result.buckets.first().end).isEqualTo(t0.plusMinutes(10))
            assertThat(result.buckets.first().partial).isTrue()
            assertThat(result.buckets.first().scanCount).isEqualTo(2)
            assertThat(result.buckets.drop(1).map { it.partial }).containsOnly(false)
        }

        @Test
        fun `a partial bucket's mean rate is not diluted by the nominal width`() {
            insertScans(12)

            val result = analysisService.analyzeRainfall(wkt, t0, t0.plusMinutes(55), bucketMinutes = 15)

            // 2 scans of 6 mm/h in a 10-minute bucket: 1.0 mm over 1/6 h is still 6 mm/h,
            // whereas spreading it over the nominal 15 minutes would read as 4 mm/h.
            assertThat(result.buckets.first().depthMm).isCloseTo(1.0, Offset.offset(0.01))
            assertThat(result.buckets.first().meanRateMmH).isCloseTo(6.0, Offset.offset(0.01))
        }

        @Test
        fun `buckets holding no scans are still emitted with zeroed statistics`() {
            insertScans(3, from = t0.plusMinutes(30))

            val result = analysisService.analyzeRainfall(wkt, t0, t0.plusMinutes(60), bucketMinutes = 15)

            assertThat(result.buckets).hasSize(4)
            val empty = result.buckets.first()
            assertThat(empty.start).isEqualTo(t0)
            assertThat(empty.scanCount).isEqualTo(0)
            assertThat(empty.depthMm).isEqualTo(0.0)
            assertThat(empty.volumeM3).isEqualTo(0.0)
            assertThat(empty.meanRateMmH).isEqualTo(0.0)
            assertThat(empty.minRateMmH).isEqualTo(0.0)
            assertThat(empty.maxRateMmH).isEqualTo(0.0)
        }

        @Test
        fun `a scan landing exactly on the period end is excluded`() {
            insertScans(1, from = t0.plusMinutes(30))

            // That scan carries the rate for 30-35 min, which is outside the period.
            val result = analysisService.analyzeRainfall(wkt, t0, t0.plusMinutes(30), bucketMinutes = 15)

            assertThat(result.buckets.last().end).isEqualTo(t0.plusMinutes(30))
            assertThat(result.buckets.map { it.scanCount }).containsOnly(0)
            assertThat(result.accumulatedRainfallMm).isEqualTo(0.0)
        }

        @Test
        fun `a bucket never reports a mean rate above its own maximum`() {
            // Rising rates, and a scan sitting exactly on the period end: this is where an
            // inclusive edge would over-fill the newest bucket and push its mean above every
            // rate the bucket contains.
            for (i in 0..12) {
                storageService.insert(
                    makeScan(
                        header = header.copy(time = t0.plusMinutes(i * 5L)),
                        values = FloatArray(15) { (2 + i).toFloat() },
                    )
                )
            }

            val result = analysisService.analyzeRainfall(wkt, t0, t0.plusMinutes(60), bucketMinutes = 15)

            assertThat(result.buckets.map { it.scanCount }).containsOnly(3)
            result.buckets.forEach { bucket ->
                assertThat(bucket.meanRateMmH).isLessThanOrEqualTo(bucket.maxRateMmH)
                assertThat(bucket.meanRateMmH).isGreaterThanOrEqualTo(bucket.minRateMmH)
            }
            assertThat(result.buckets.last().meanRateMmH).isCloseTo(12.0, Offset.offset(0.01))
        }

        @Test
        fun `min and max fold across the scans in a bucket`() {
            storageService.insert(makeScan(header = header.copy(time = t0), values = FloatArray(15) { 2.0f }))
            storageService.insert(
                makeScan(
                    header = header.copy(time = t0.plusMinutes(5)),
                    values = FloatArray(15) { if (it == 0) 9.0f else 1.0f },
                )
            )

            val result = analysisService.analyzeRainfall(wkt, t0, t0.plusMinutes(10), bucketMinutes = 10)

            assertThat(result.buckets).hasSize(1)
            assertThat(result.buckets[0].scanCount).isEqualTo(2)
            assertThat(result.buckets[0].minRateMmH).isCloseTo(1.0, Offset.offset(0.01))
            assertThat(result.buckets[0].maxRateMmH).isCloseTo(9.0, Offset.offset(0.01))
        }

        @Test
        fun `bucketMinutes is echoed back`() {
            insertScans(12)

            val result = analysisService.analyzeRainfall(wkt, t0, t0.plusMinutes(55), bucketMinutes = 15)

            assertThat(result.bucketMinutes).isEqualTo(15)
        }

        @Test
        fun `rejects a bucket shorter than the scan interval`() {
            assertThatThrownBy { analysisService.analyzeRainfall(wkt, t0, t0.plusHours(1), bucketMinutes = 3) }
                .isInstanceOf(InvalidQueryException::class.java)
                .hasMessage("bucketMinutes must be at least 5")
        }

        @Test
        fun `rejects a bucket that is not a whole number of scans`() {
            assertThatThrownBy { analysisService.analyzeRainfall(wkt, t0, t0.plusHours(1), bucketMinutes = 7) }
                .isInstanceOf(InvalidQueryException::class.java)
                .hasMessage("bucketMinutes must be a multiple of 5")
        }

        @Test
        fun `rejects a bucket wider than the selected period`() {
            assertThatThrownBy { analysisService.analyzeRainfall(wkt, t0, t0.plusMinutes(30), bucketMinutes = 60) }
                .isInstanceOf(InvalidQueryException::class.java)
                .hasMessage("bucketMinutes must not exceed the selected period")
        }

        @Test
        fun `rejects a reversed period`() {
            assertThatThrownBy { analysisService.analyzeRainfall(wkt, t0.plusHours(1), t0) }
                .isInstanceOf(InvalidQueryException::class.java)
                .hasMessage("from must be before to")
        }
    }

    @Nested
    inner class ListFrames {

        private fun insertScans(count: Int, from: ZonedDateTime = t0, rateMmPerHour: Float = 6.0f) {
            for (i in 0 until count) {
                storageService.insert(
                    makeScan(
                        header = makeHeader().copy(time = from.plusMinutes(i * 5L)),
                        values = FloatArray(15) { rateMmPerHour },
                    )
                )
            }
        }

        @Test
        fun `un-bucketed frames are one per scan and five minutes wide`() {
            insertScans(3)

            val frames = analysisService.listFrames(t0, t0.plusMinutes(15), bucketMinutes = null, scale = null)

            assertThat(frames.frames).hasSize(3)
            assertThat(frames.bucketMinutes).isEqualTo(5)
            assertThat(frames.frames[0].start).isEqualTo(t0)
            assertThat(frames.frames[0].end).isEqualTo(t0.plusMinutes(5))
            assertThat(frames.frames.map { it.partial }).containsOnly(false)
            assertThat(frames.scaleMaxMm).isNull()
        }

        @Test
        fun `bucketed frames cover the period even where scans are missing`() {
            insertScans(3, from = t0.plusMinutes(30))

            val frames = analysisService.listFrames(t0, t0.plusMinutes(60), bucketMinutes = 15, scale = null)

            assertThat(frames.frames).hasSize(4)
            assertThat(frames.frames.first().start).isEqualTo(t0)
            assertThat(frames.frames.last().end).isEqualTo(t0.plusMinutes(60))
            assertThat(frames.bucketMinutes).isEqualTo(15)
        }

        @Test
        fun `scaleMaxMm stays null for the rate and fixed scales`() {
            insertScans(3)

            assertThat(
                analysisService.listFrames(t0, t0.plusMinutes(30), 15, ColorScaleMode.RATE).scaleMaxMm
            ).isNull()
            assertThat(
                analysisService.listFrames(t0, t0.plusMinutes(30), 15, ColorScaleMode.FIXED).scaleMaxMm
            ).isNull()
        }

        @Test
        fun `autofit reports the deepest bucket in the period so frames share one scale`() {
            insertScans(3, from = t0, rateMmPerHour = 6.0f)
            insertScans(1, from = t0.plusMinutes(30), rateMmPerHour = 60.0f)

            val frames = analysisService.listFrames(t0, t0.plusMinutes(60), 15, ColorScaleMode.AUTOFIT)

            // The 30–45 bucket holds one 60 mm/h scan: 60 × 5/60 = 5 mm, deeper than
            // the 3 × 6 mm/h scans in the first bucket (1.5 mm).
            assertThat(frames.scaleMaxMm).isNotNull()
            assertThat(frames.scaleMaxMm!!).isCloseTo(5.0, Offset.offset(0.01))
        }

        @Test
        fun `autofit over a dry period still yields a usable maximum`() {
            val frames = analysisService.listFrames(t0, t0.plusMinutes(60), 15, ColorScaleMode.AUTOFIT)

            // Floored at the lowest band, so the overlay cannot reject the value frames just handed out.
            assertThat(frames.scaleMaxMm).isNotNull()
            assertThat(frames.scaleMaxMm!!).isCloseTo(0.2, Offset.offset(1e-9))
            assertThat(
                analysisService.renderOverlayPng(t0, t0.plusMinutes(15), ColorScaleMode.AUTOFIT, frames.scaleMaxMm)
            ).isNull()
        }

        @Test
        fun `autofit never floors below a real measurement`() {
            insertScans(3, rateMmPerHour = 60.0f)

            val frames = analysisService.listFrames(t0, t0.plusMinutes(60), 15, ColorScaleMode.AUTOFIT)

            assertThat(frames.scaleMaxMm!!).isCloseTo(15.0, Offset.offset(0.01))
        }

        @Test
        fun `rejects un-bucketed autofit, which would union every scan separately`() {
            assertThatThrownBy { analysisService.listFrames(t0, t0.plusMinutes(60), null, ColorScaleMode.AUTOFIT) }
                .isInstanceOf(InvalidQueryException::class.java)
                .hasMessage("bucketMinutes is required for autofit")
        }

        @Test
        fun `rejects a bucket wider than the selected period`() {
            assertThatThrownBy { analysisService.listFrames(t0, t0.plusMinutes(30), 60, null) }
                .isInstanceOf(InvalidQueryException::class.java)
                .hasMessage("bucketMinutes must not exceed the selected period")
        }
    }
}
