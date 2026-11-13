package si.rainalator.api

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
import jakarta.ws.rs.core.Response
import si.rainalator.analysis.RainfallAnalysisService
import si.rainalator.api.dto.FramesResponse
import si.rainalator.api.dto.RainfallQueryRequest
import si.rainalator.api.dto.RainfallQueryResponse
import si.rainalator.ingestion.ProjectionConverter
import si.rainalator.ingestion.RadarScan
import si.rainalator.ingestion.SrdHeader
import si.rainalator.storage.RasterStorageService
import java.sql.DriverManager
import java.time.ZoneOffset
import java.time.ZonedDateTime

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RainfallResourceTest {

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

    private lateinit var resource: RainfallResource
    private lateinit var storageService: RasterStorageService
    private lateinit var dataSource: javax.sql.DataSource

    private val projectionConverter = ProjectionConverter()
    private val t0 = ZonedDateTime.of(2026, 3, 30, 12, 0, 0, 0, ZoneOffset.UTC)

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
        resource = RainfallResource(RainfallAnalysisService(dataSource, storageService))

        dataSource.connection.use { c ->
            c.createStatement().execute("DELETE FROM radar_scans")
        }
    }

    private fun makeHeader(width: Int = 5, height: Int = 3): SrdHeader = SrdHeader(
        domain = "TEST",
        sourceRadars = listOf("T1"),
        time = t0,
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

    @Suppress("UNCHECKED_CAST")
    private fun errorOf(response: Response): String =
        (response.entity as Map<String, String>).getValue("error")

    @Nested
    inner class PostRainfallQuery {

        @Test
        fun `returns 200 with valid query`() {
            storageService.insert(makeScan())
            val wkt = bboxPolygonWkt(makeHeader())

            val response = resource.queryRainfall(RainfallQueryRequest(
                polygon = wkt,
                from = t0.minusMinutes(1).toString(),
                to = t0.plusMinutes(1).toString(),
            ))

            assertThat(response.status).isEqualTo(200)
            val body = response.entity as RainfallQueryResponse
            assertThat(body.bucketCount).isEqualTo(1)
            assertThat(body.buckets).hasSize(1)
            assertThat(body.bucketMinutes).isEqualTo(5)
            assertThat(body.accumulatedRainfallMm).isGreaterThan(0.0)
        }

        @Test
        fun `a bucketed query folds the scans into buckets`() {
            for (i in 0..11) {
                storageService.insert(makeScan(header = makeHeader().copy(time = t0.plusMinutes(i * 5L))))
            }
            val wkt = bboxPolygonWkt(makeHeader())

            val response = resource.queryRainfall(RainfallQueryRequest(
                polygon = wkt,
                from = t0.toString(),
                to = t0.plusMinutes(55).toString(),
                bucketMinutes = 15,
            ))

            assertThat(response.status).isEqualTo(200)
            val body = response.entity as RainfallQueryResponse
            assertThat(body.bucketMinutes).isEqualTo(15)
            assertThat(body.bucketCount).isEqualTo(4)
            assertThat(body.buckets.first().partial).isTrue()
            assertThat(body.buckets.sumOf { it.scanCount }).isEqualTo(11)
            assertThat(body.buckets.sumOf { it.depthMm })
                .isCloseTo(body.accumulatedRainfallMm, Offset.offset(1e-9))
        }

        @Test
        fun `bucket edges are serialised as UTC ISO-8601`() {
            storageService.insert(makeScan())
            val wkt = bboxPolygonWkt(makeHeader())

            val response = resource.queryRainfall(RainfallQueryRequest(
                polygon = wkt,
                from = t0.toString(),
                to = t0.plusMinutes(5).toString(),
            ))

            val body = response.entity as RainfallQueryResponse
            assertThat(body.buckets.first().start).endsWith("Z")
            assertThat(body.buckets.first().end).endsWith("Z")
        }

        @Test
        fun `returns 400 for missing polygon`() {
            val response = resource.queryRainfall(RainfallQueryRequest(
                polygon = "",
                from = t0.minusMinutes(1).toString(),
                to = t0.plusMinutes(1).toString(),
            ))
            assertThat(response.status).isEqualTo(400)
            assertThat(errorOf(response)).isEqualTo("polygon is required")
        }

        @Test
        fun `returns 400 for invalid time range`() {
            val response = resource.queryRainfall(RainfallQueryRequest(
                polygon = "POLYGON((0 0, 1 0, 1 1, 0 1, 0 0))",
                from = t0.plusMinutes(1).toString(),
                to = t0.minusMinutes(1).toString(),
            ))
            assertThat(response.status).isEqualTo(400)
            assertThat(errorOf(response)).isEqualTo("from must be before to")
        }

        @Test
        fun `returns 400 for an unparseable date`() {
            val response = resource.queryRainfall(RainfallQueryRequest(
                polygon = "POLYGON((0 0, 1 0, 1 1, 0 1, 0 0))",
                from = "not-a-date",
                to = t0.toString(),
            ))
            assertThat(response.status).isEqualTo(400)
            assertThat(errorOf(response)).startsWith("Invalid date format")
        }

        @Test
        fun `returns 400 when bucketMinutes is below the scan interval`() {
            val response = resource.queryRainfall(RainfallQueryRequest(
                polygon = "POLYGON((0 0, 1 0, 1 1, 0 1, 0 0))",
                from = t0.toString(),
                to = t0.plusHours(1).toString(),
                bucketMinutes = 3,
            ))
            assertThat(response.status).isEqualTo(400)
            assertThat(errorOf(response)).isEqualTo("bucketMinutes must be at least 5")
        }

        @Test
        fun `returns 400 when bucketMinutes is not a multiple of the scan interval`() {
            val response = resource.queryRainfall(RainfallQueryRequest(
                polygon = "POLYGON((0 0, 1 0, 1 1, 0 1, 0 0))",
                from = t0.toString(),
                to = t0.plusHours(1).toString(),
                bucketMinutes = 7,
            ))
            assertThat(response.status).isEqualTo(400)
            assertThat(errorOf(response)).isEqualTo("bucketMinutes must be a multiple of 5")
        }

        @Test
        fun `returns 400 when bucketMinutes exceeds the selected period`() {
            val response = resource.queryRainfall(RainfallQueryRequest(
                polygon = "POLYGON((0 0, 1 0, 1 1, 0 1, 0 0))",
                from = t0.toString(),
                to = t0.plusMinutes(30).toString(),
                bucketMinutes = 60,
            ))
            assertThat(response.status).isEqualTo(400)
            assertThat(errorOf(response)).isEqualTo("bucketMinutes must not exceed the selected period")
        }

        @Test
        fun `returns 200 with no buckets when no data in range`() {
            val response = resource.queryRainfall(RainfallQueryRequest(
                polygon = "POLYGON((0 0, 1 0, 1 1, 0 1, 0 0))",
                from = t0.minusHours(2).toString(),
                to = t0.minusHours(1).toString(),
            ))

            assertThat(response.status).isEqualTo(200)
            val body = response.entity as RainfallQueryResponse
            assertThat(body.bucketCount).isEqualTo(0)
        }
    }

    @Nested
    inner class GetFrames {

        @Test
        fun `returns one frame per scan when un-bucketed`() {
            storageService.insert(makeScan())
            storageService.insert(makeScan(header = makeHeader().copy(time = t0.plusMinutes(5))))

            val response = resource.getFrames(
                t0.minusMinutes(1).toString(),
                t0.plusMinutes(6).toString(),
                null,
                null,
            )

            assertThat(response.status).isEqualTo(200)
            val body = response.entity as FramesResponse
            assertThat(body.count).isEqualTo(2)
            assertThat(body.frames).hasSize(2)
            assertThat(body.bucketMinutes).isEqualTo(5)
            assertThat(body.scaleMaxMm).isNull()
            assertThat(body.frames.first().start).endsWith("Z")
        }

        @Test
        fun `returns empty list when no scans`() {
            val response = resource.getFrames(
                t0.minusHours(2).toString(),
                t0.minusHours(1).toString(),
                null,
                null,
            )

            assertThat(response.status).isEqualTo(200)
            val body = response.entity as FramesResponse
            assertThat(body.count).isEqualTo(0)
        }

        @Test
        fun `returns bucket frames when bucketMinutes is supplied`() {
            storageService.insert(makeScan())

            val response = resource.getFrames(
                t0.toString(),
                t0.plusMinutes(60).toString(),
                "15",
                null,
            )

            val body = response.entity as FramesResponse
            assertThat(body.count).isEqualTo(4)
            assertThat(body.bucketMinutes).isEqualTo(15)
        }

        @Test
        fun `reports a shared scale maximum for autofit`() {
            storageService.insert(makeScan(values = FloatArray(15) { 60.0f }))

            val response = resource.getFrames(
                t0.toString(),
                t0.plusMinutes(60).toString(),
                "15",
                "autofit",
            )

            val body = response.entity as FramesResponse
            assertThat(body.scaleMaxMm).isNotNull()
            assertThat(body.scaleMaxMm!!).isCloseTo(5.0, Offset.offset(0.01))
        }

        @Test
        fun `returns 400 for un-bucketed autofit`() {
            val response = resource.getFrames(t0.toString(), t0.plusHours(1).toString(), null, "autofit")

            assertThat(response.status).isEqualTo(400)
            assertThat(errorOf(response)).isEqualTo("bucketMinutes is required for autofit")
        }

        @Test
        fun `returns 400 for an unknown scale`() {
            val response = resource.getFrames(t0.toString(), t0.plusHours(1).toString(), null, "rainbow")

            assertThat(response.status).isEqualTo(400)
            assertThat(errorOf(response)).isEqualTo("scale must be one of rate, fixed, autofit")
        }

        @Test
        fun `returns 400 for a non-numeric bucketMinutes`() {
            val response = resource.getFrames(t0.toString(), t0.plusHours(1).toString(), "half an hour", null)

            assertThat(response.status).isEqualTo(400)
            assertThat(errorOf(response)).isEqualTo("bucketMinutes must be a whole number of minutes")
        }

        @Test
        fun `returns 400 when bucketMinutes exceeds the selected period`() {
            val response = resource.getFrames(t0.toString(), t0.plusMinutes(30).toString(), "60", null)

            assertThat(response.status).isEqualTo(400)
            assertThat(errorOf(response)).isEqualTo("bucketMinutes must not exceed the selected period")
        }
    }

    @Nested
    inner class GetOverlay {

        @Test
        fun `returns PNG with correct content type`() {
            storageService.insert(makeScan())

            val response = resource.getOverlay(t0.toString(), t0.plusMinutes(5).toString(), "rate", null)

            assertThat(response.status).isEqualTo(200)
            assertThat(response.mediaType.toString()).isEqualTo("image/png")
        }

        @Test
        fun `returns 404 when the bucket holds no scan`() {
            val response = resource.getOverlay(
                t0.minusHours(5).toString(),
                t0.minusHours(5).plusMinutes(5).toString(),
                "rate",
                null,
            )
            assertThat(response.status).isEqualTo(404)
        }

        @Test
        fun `includes geo-bounds headers`() {
            storageService.insert(makeScan())

            val response = resource.getOverlay(t0.toString(), t0.plusMinutes(5).toString(), "rate", null)

            assertThat(response.getHeaderString("X-Bounds-South")).isNotNull()
            assertThat(response.getHeaderString("X-Bounds-West")).isNotNull()
            assertThat(response.getHeaderString("X-Bounds-North")).isNotNull()
            assertThat(response.getHeaderString("X-Bounds-East")).isNotNull()
        }

        @Test
        fun `includes legend headers describing the painted scale`() {
            storageService.insert(makeScan())

            val response = resource.getOverlay(t0.toString(), t0.plusMinutes(5).toString(), "rate", null)

            assertThat(response.getHeaderString("X-Legend-Unit")).isEqualTo("mm/h")
            assertThat(response.getHeaderString("X-Legend-Thresholds").split(","))
                .hasSize(14)
                .startsWith("0.4")
            assertThat(response.getHeaderString("X-Legend-Colors").split(","))
                .hasSize(15)
                .startsWith("085AFE")
        }

        @Test
        fun `the fixed scale advertises absolute mm thresholds`() {
            storageService.insert(makeScan())

            val response = resource.getOverlay(t0.toString(), t0.plusMinutes(5).toString(), "fixed", null)

            assertThat(response.getHeaderString("X-Legend-Unit")).isEqualTo("mm")
            assertThat(response.getHeaderString("X-Legend-Thresholds"))
                .isEqualTo("0.2,0.5,1,2,4,6,10,15,25,40,60,90,130,200")
        }

        @Test
        fun `autofit legend thresholds are rounded for display`() {
            storageService.insert(makeScan())

            val response = resource.getOverlay(
                t0.toString(), t0.plusMinutes(5).toString(), "autofit", "2.75",
            )

            // Rescaling leaves float noise (0.08249999999999999) that the legend must not show.
            assertThat(response.getHeaderString("X-Legend-Thresholds"))
                .isEqualTo("0.00275,0.006875,0.01375,0.0275,0.055,0.0825,0.1375,0.20625,0.34375,0.55,0.825,1.2375,1.7875,2.75")
        }

        @Test
        fun `returns 400 when start is not before end`() {
            val response = resource.getOverlay(t0.toString(), t0.toString(), "rate", null)

            assertThat(response.status).isEqualTo(400)
            assertThat(errorOf(response)).isEqualTo("start must be before end")
        }

        @Test
        fun `returns 400 for autofit without a scaleMax`() {
            val response = resource.getOverlay(t0.toString(), t0.plusMinutes(5).toString(), "autofit", null)

            assertThat(response.status).isEqualTo(400)
            assertThat(errorOf(response)).isEqualTo("scaleMax is required for autofit")
        }

        @Test
        fun `returns 400 for autofit with a non-positive scaleMax`() {
            val response = resource.getOverlay(t0.toString(), t0.plusMinutes(5).toString(), "autofit", "0")

            assertThat(response.status).isEqualTo(400)
            assertThat(errorOf(response)).isEqualTo("scaleMax is required for autofit")
        }

        @Test
        fun `returns 400 for an unparseable timestamp`() {
            val response = resource.getOverlay("yesterday", t0.toString(), "rate", null)

            assertThat(response.status).isEqualTo(400)
            assertThat(errorOf(response)).startsWith("Invalid date format")
        }
    }
}
