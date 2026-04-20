package si.rainalator.analysis

import org.assertj.core.api.Assertions.assertThat
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
        analysisService = RainfallAnalysisService(dataSource)

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
    inner class RenderOverlay {

        @Test
        fun `returns null when no scan at given timestamp`() {
            val result = analysisService.renderOverlayPng(t0)
            assertThat(result).isNull()
        }

        @Test
        fun `returns PNG bytes for existing scan`() {
            storageService.insert(makeScan())

            val result = analysisService.renderOverlayPng(t0)

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

            val result = analysisService.renderOverlayPng(t0)!!
            val image = ImageIO.read(result.pngBytes.inputStream())

            assertThat(image.width).isEqualTo(5)
            assertThat(image.height).isEqualTo(3)
        }

        @Test
        fun `transparent pixels for nodata values`() {
            // All NaN
            val values = FloatArray(15) { Float.NaN }
            storageService.insert(makeScan(values = values))

            val result = analysisService.renderOverlayPng(t0)!!
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

            val result = analysisService.renderOverlayPng(t0)!!
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

            val result = analysisService.renderOverlayPng(t0)!!

            assertThat(result.bounds.south).isCloseTo(sw.lat, Offset.offset(0.001))
            assertThat(result.bounds.west).isCloseTo(sw.lon, Offset.offset(0.001))
            assertThat(result.bounds.north).isCloseTo(ne.lat, Offset.offset(0.001))
            assertThat(result.bounds.east).isCloseTo(ne.lon, Offset.offset(0.001))
        }
    }
}
