package si.rainalator.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import si.rainalator.config.AppConfig
import si.rainalator.storage.RasterStorageService
import java.sql.DriverManager

/**
 * Integration test for RadarDataScheduler: parses a real SRD file
 * and stores it via RasterStorageService into a PostGIS testcontainer.
 * Tests the full pipeline without HTTP (uses the sample file directly).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RadarDataSchedulerTest {

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

    private lateinit var storageService: RasterStorageService

    @BeforeAll
    fun setupSchema() {
        postgis.start()
        DriverManager.getConnection(postgis.jdbcUrl, postgis.username, postgis.password).use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("CREATE EXTENSION IF NOT EXISTS postgis")
                stmt.execute("CREATE EXTENSION IF NOT EXISTS postgis_raster")
                stmt.execute("""
                    CREATE TABLE radar_scans (
                        id            BIGSERIAL PRIMARY KEY,
                        scan_time     TIMESTAMPTZ NOT NULL,
                        ingested_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        source_radars TEXT[],
                        raster_data   RASTER NOT NULL,
                        bbox          GEOMETRY(Polygon, 4326),
                        scan_metadata JSONB
                    )
                """)
                stmt.execute("ALTER TABLE radar_scans ADD CONSTRAINT uq_scan_time UNIQUE (scan_time)")
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
        storageService = RasterStorageService(pgDs)

        pgDs.connection.use { c ->
            c.createStatement().execute("DELETE FROM radar_scans")
        }
    }

    @Test
    fun `full pipeline - parse sample SRD file and store as raster`() {
        val srdContent = javaClass.classLoader.getResourceAsStream("sample-si0-rrg.srd")!!
            .bufferedReader().readText()

        val parser = SrdParser()
        val scan = parser.parse(srdContent)

        storageService.insert(scan)

        assertThat(storageService.countScans()).isEqualTo(1)

        // Verify the stored raster has correct properties
        val pgDs = org.postgresql.ds.PGSimpleDataSource()
        pgDs.setUrl(postgis.jdbcUrl)
        pgDs.user = postgis.username
        pgDs.password = postgis.password

        pgDs.connection.use { conn ->
            val rs = conn.createStatement().executeQuery("""
                SELECT
                    ST_Width(raster_data) as w,
                    ST_Height(raster_data) as h,
                    ST_SRID(raster_data) as srid,
                    (ST_SummaryStats(raster_data, 1, true)).count as pixel_count,
                    (ST_SummaryStats(raster_data, 1, true)).mean as mean_val
                FROM radar_scans LIMIT 1
            """)
            assertThat(rs.next()).isTrue()
            assertThat(rs.getInt("w")).isEqualTo(401)
            assertThat(rs.getInt("h")).isEqualTo(301)
            assertThat(rs.getInt("srid")).isEqualTo(4326)

            val pixelCount = rs.getLong("pixel_count")
            // Should have some valid pixels (not all nodata)
            assertThat(pixelCount).isGreaterThan(0)
        }
    }

    @Test
    fun `parser correctly identifies rain in sample file`() {
        val srdContent = javaClass.classLoader.getResourceAsStream("sample-si0-rrg.srd")!!
            .bufferedReader().readText()

        val scan = SrdParser().parse(srdContent)

        // Count cells with actual rain (> 0 and not NaN)
        val rainCells = scan.values.count { !it.isNaN() && it > 0.0f }
        val noCells = scan.values.count { it == 0.0f }
        val nanCells = scan.values.count { it.isNaN() }

        // The sample file should have some rain
        assertThat(rainCells).isGreaterThan(0)
        // Most cells should be zero (no rain) or NaN (nodata)
        assertThat(noCells + nanCells).isGreaterThan(rainCells)
        // Total should equal grid size
        assertThat(rainCells + noCells + nanCells).isEqualTo(401 * 301)
    }
}
