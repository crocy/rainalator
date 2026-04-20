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
import si.rainalator.analysis.RainfallAnalysisService
import si.rainalator.api.dto.RainfallQueryRequest
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
        val analysisService = RainfallAnalysisService(dataSource)
        resource = RainfallResource(analysisService, storageService)

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
            val body = response.entity as si.rainalator.api.dto.RainfallQueryResponse
            assertThat(body.scanCount).isEqualTo(1)
            assertThat(body.scans).hasSize(1)
            assertThat(body.accumulatedRainfallMm).isGreaterThan(0.0)
        }

        @Test
        fun `returns 400 for missing polygon`() {
            val response = resource.queryRainfall(RainfallQueryRequest(
                polygon = "",
                from = t0.minusMinutes(1).toString(),
                to = t0.plusMinutes(1).toString(),
            ))
            assertThat(response.status).isEqualTo(400)
        }

        @Test
        fun `returns 400 for invalid time range`() {
            val response = resource.queryRainfall(RainfallQueryRequest(
                polygon = "POLYGON((0 0, 1 0, 1 1, 0 1, 0 0))",
                from = t0.plusMinutes(1).toString(),
                to = t0.minusMinutes(1).toString(),
            ))
            assertThat(response.status).isEqualTo(400)
        }

        @Test
        fun `returns 200 with empty scans when no data in range`() {
            val response = resource.queryRainfall(RainfallQueryRequest(
                polygon = "POLYGON((0 0, 1 0, 1 1, 0 1, 0 0))",
                from = t0.minusHours(2).toString(),
                to = t0.minusHours(1).toString(),
            ))

            assertThat(response.status).isEqualTo(200)
            val body = response.entity as si.rainalator.api.dto.RainfallQueryResponse
            assertThat(body.scanCount).isEqualTo(0)
        }
    }

    @Nested
    inner class GetScanTimes {

        @Test
        fun `returns scan times in range`() {
            storageService.insert(makeScan())
            storageService.insert(makeScan(header = makeHeader().copy(time = t0.plusMinutes(5))))

            val response = resource.getScanTimes(
                t0.minusMinutes(1).toString(),
                t0.plusMinutes(6).toString(),
            )

            assertThat(response.status).isEqualTo(200)
            val body = response.entity as si.rainalator.api.dto.ScanTimesResponse
            assertThat(body.count).isEqualTo(2)
        }

        @Test
        fun `returns empty list when no scans`() {
            val response = resource.getScanTimes(
                t0.minusHours(2).toString(),
                t0.minusHours(1).toString(),
            )

            assertThat(response.status).isEqualTo(200)
            val body = response.entity as si.rainalator.api.dto.ScanTimesResponse
            assertThat(body.count).isEqualTo(0)
        }
    }

    @Nested
    inner class GetOverlay {

        @Test
        fun `returns PNG with correct content type`() {
            storageService.insert(makeScan())

            val response = resource.getOverlay(t0.toString())

            assertThat(response.status).isEqualTo(200)
            assertThat(response.mediaType.toString()).isEqualTo("image/png")
        }

        @Test
        fun `returns 404 for missing timestamp`() {
            val response = resource.getOverlay(t0.minusHours(5).toString())
            assertThat(response.status).isEqualTo(404)
        }

        @Test
        fun `includes geo-bounds headers`() {
            storageService.insert(makeScan())

            val response = resource.getOverlay(t0.toString())

            assertThat(response.getHeaderString("X-Bounds-South")).isNotNull()
            assertThat(response.getHeaderString("X-Bounds-West")).isNotNull()
            assertThat(response.getHeaderString("X-Bounds-North")).isNotNull()
            assertThat(response.getHeaderString("X-Bounds-East")).isNotNull()
        }
    }
}
