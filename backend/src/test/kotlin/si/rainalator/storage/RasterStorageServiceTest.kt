package si.rainalator.storage

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
import si.rainalator.ingestion.RadarScan
import si.rainalator.ingestion.SrdHeader
import java.sql.DriverManager
import java.time.ZoneOffset
import java.time.ZonedDateTime
import javax.sql.DataSource

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RasterStorageServiceTest {

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

    private lateinit var service: RasterStorageService
    private lateinit var dataSource: DataSource

    @BeforeAll
    fun setupSchema() {
        postgis.start()
        val conn = DriverManager.getConnection(postgis.jdbcUrl, postgis.username, postgis.password)
        conn.use { c ->
            c.createStatement().use { stmt ->
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

        service = RasterStorageService(dataSource)

        // Clean table between tests
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

    @Nested
    inner class InsertAndCount {

        @Test
        fun `insert a scan and count returns 1`() {
            service.insert(makeScan())
            assertThat(service.countScans()).isEqualTo(1)
        }

        @Test
        fun `insert two scans with different times and count returns 2`() {
            val scan1 = makeScan()
            val header2 = makeHeader().copy(
                time = ZonedDateTime.of(2026, 3, 30, 12, 5, 0, 0, ZoneOffset.UTC)
            )
            val scan2 = makeScan(header = header2)

            service.insert(scan1)
            service.insert(scan2)
            assertThat(service.countScans()).isEqualTo(2)
        }
    }

    @Nested
    inner class FindScanTimes {

        @Test
        fun `returns scan times within range`() {
            val t1 = ZonedDateTime.of(2026, 3, 30, 10, 0, 0, 0, ZoneOffset.UTC)
            val t2 = ZonedDateTime.of(2026, 3, 30, 10, 5, 0, 0, ZoneOffset.UTC)
            val t3 = ZonedDateTime.of(2026, 3, 30, 10, 10, 0, 0, ZoneOffset.UTC)

            service.insert(makeScan(header = makeHeader().copy(time = t1)))
            service.insert(makeScan(header = makeHeader().copy(time = t2)))
            service.insert(makeScan(header = makeHeader().copy(time = t3)))

            val result = service.findScanTimes(
                from = ZonedDateTime.of(2026, 3, 30, 10, 0, 0, 0, ZoneOffset.UTC),
                to = ZonedDateTime.of(2026, 3, 30, 10, 5, 0, 0, ZoneOffset.UTC),
            )
            assertThat(result).hasSize(2)
        }

        @Test
        fun `returns empty list when no scans in range`() {
            service.insert(makeScan())
            val result = service.findScanTimes(
                from = ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                to = ZonedDateTime.of(2025, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC),
            )
            assertThat(result).isEmpty()
        }
    }

    @Nested
    inner class RasterData {

        @Test
        fun `stored raster has correct dimensions`() {
            service.insert(makeScan())

            dataSource.connection.use { conn ->
                val rs = conn.createStatement().executeQuery("""
                    SELECT ST_Width(raster_data), ST_Height(raster_data)
                    FROM radar_scans LIMIT 1
                """)
                assertThat(rs.next()).isTrue()
                assertThat(rs.getInt(1)).isEqualTo(5)  // width
                assertThat(rs.getInt(2)).isEqualTo(3)  // height
            }
        }

        @Test
        fun `stored raster has SRID 4326`() {
            service.insert(makeScan())

            dataSource.connection.use { conn ->
                val rs = conn.createStatement().executeQuery("""
                    SELECT ST_SRID(raster_data) FROM radar_scans LIMIT 1
                """)
                assertThat(rs.next()).isTrue()
                assertThat(rs.getInt(1)).isEqualTo(4326)
            }
        }

        @Test
        fun `stored raster preserves uniform values via ST_SummaryStats`() {
            // All values are 1.0 mm/h
            service.insert(makeScan())

            dataSource.connection.use { conn ->
                val rs = conn.createStatement().executeQuery("""
                    SELECT (ST_SummaryStats(raster_data, 1, true)).*
                    FROM radar_scans LIMIT 1
                """)
                assertThat(rs.next()).isTrue()
                val count = rs.getLong("count")
                val sum = rs.getDouble("sum")
                val mean = rs.getDouble("mean")

                assertThat(count).isEqualTo(15) // 5x3 grid, all non-NaN
                assertThat(mean).isCloseTo(1.0, Offset.offset(0.01))
                assertThat(sum).isCloseTo(15.0, Offset.offset(0.1))
            }
        }

        @Test
        fun `stored raster handles NaN values as nodata`() {
            val values = FloatArray(15) { if (it < 5) Float.NaN else 2.0f }
            // First 5 cells are NaN, rest are 2.0
            service.insert(makeScan(values = values))

            dataSource.connection.use { conn ->
                val rs = conn.createStatement().executeQuery("""
                    SELECT (ST_SummaryStats(raster_data, 1, true)).*
                    FROM radar_scans LIMIT 1
                """)
                assertThat(rs.next()).isTrue()
                val count = rs.getLong("count")
                val mean = rs.getDouble("mean")

                // exclude_nodata_value=true should skip NaN cells
                assertThat(count).isEqualTo(10) // 15 - 5 NaN = 10
                assertThat(mean).isCloseTo(2.0, Offset.offset(0.01))
            }
        }

        @Test
        fun `bbox geometry is stored correctly`() {
            service.insert(makeScan())

            dataSource.connection.use { conn ->
                val rs = conn.createStatement().executeQuery("""
                    SELECT ST_SRID(bbox), ST_AsText(bbox) FROM radar_scans LIMIT 1
                """)
                assertThat(rs.next()).isTrue()
                assertThat(rs.getInt(1)).isEqualTo(4326)
                val wkt = rs.getString(2)
                assertThat(wkt).startsWith("POLYGON")
            }
        }
    }

    @Nested
    inner class FullSizeScan {

        @Test
        fun `can store and query a 401x301 raster`() {
            val header = makeHeader(width = 401, height = 301).copy(
                shiftX = -4.0,
                shiftY = -6.0,
            )
            // Create a scan with varying values
            val values = FloatArray(401 * 301) { i ->
                val row = i / 401
                val col = i % 401
                if (col < 10 && row < 10) 5.0f else 0.0f
            }
            service.insert(RadarScan(header, values))

            assertThat(service.countScans()).isEqualTo(1)

            dataSource.connection.use { conn ->
                val rs = conn.createStatement().executeQuery("""
                    SELECT ST_Width(raster_data), ST_Height(raster_data)
                    FROM radar_scans LIMIT 1
                """)
                assertThat(rs.next()).isTrue()
                assertThat(rs.getInt(1)).isEqualTo(401)
                assertThat(rs.getInt(2)).isEqualTo(301)
            }
        }
    }
}
