package si.rainalator.analysis

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import si.rainalator.ingestion.ProjectionConverter
import si.rainalator.ingestion.SrdParser
import si.rainalator.storage.RasterStorageService
import java.sql.DriverManager

/**
 * Verifies ST_Clip-based analysis for polygons smaller than the raster —
 * the everyday case of a user-drawn shape — using the real SI0 sample scan.
 * All other analysis tests use the full raster bbox, which would not catch
 * a clip that silently ignores the polygon.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RainfallAnalysisSubPolygonTest {

    companion object {
        val postgis: PostgreSQLContainer<*> = PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:16-3.4")
                .asCompatibleSubstituteFor("postgres")
        )
            .withDatabaseName("rainalator_test")
            .withUsername("test")
            .withPassword("test")
    }

    private lateinit var analysisService: RainfallAnalysisService

    private val projectionConverter = ProjectionConverter()

    private val scan by lazy {
        val body = javaClass.getResourceAsStream("/sample-si0-rrg.srd")!!.bufferedReader().readText()
        SrdParser().parse(body)
    }

    private data class Stats(val count: Long, val sum: Double, val mean: Double, val min: Double, val max: Double)

    @BeforeAll
    fun setup() {
        postgis.start()
        DriverManager.getConnection(postgis.jdbcUrl, postgis.username, postgis.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE EXTENSION IF NOT EXISTS postgis")
                stmt.execute("CREATE EXTENSION IF NOT EXISTS postgis_raster")
                stmt.execute(
                    """
                    CREATE TABLE radar_scans (
                        scan_time     TIMESTAMPTZ NOT NULL PRIMARY KEY,
                        ingested_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        source_radars TEXT[],
                        raster_data   RASTER NOT NULL,
                        bbox          GEOMETRY(Polygon, 4326),
                        scan_metadata JSONB
                    )
                    """
                )
            }
        }

        val pgDs = org.postgresql.ds.PGSimpleDataSource()
        pgDs.setUrl(postgis.jdbcUrl)
        pgDs.user = postgis.username
        pgDs.password = postgis.password

        RasterStorageService(pgDs).insert(scan)
        analysisService = RainfallAnalysisService(pgDs)
    }

    @AfterAll
    fun teardown() {
        postgis.stop()
    }

    private fun rectWkt(lonMin: Double, latMin: Double, lonMax: Double, latMax: Double): String =
        "POLYGON(($lonMin $latMin, $lonMax $latMin, $lonMax $latMax, $lonMin $latMax, $lonMin $latMin))"

    /** Reference stats computed directly from the parsed grid via the raster's linear georeference. */
    private fun expectedStats(lonMin: Double, latMin: Double, lonMax: Double, latMax: Double): Stats {
        val header = scan.header
        val (sw, ne) = projectionConverter.gridBounds(header)
        val scaleX = (ne.lon - sw.lon) / header.width
        val scaleY = -(ne.lat - sw.lat) / header.height

        var count = 0L
        var sum = 0.0
        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE
        for (row in 0 until header.height) {
            for (col in 0 until header.width) {
                val lon = sw.lon + (col + 0.5) * scaleX
                val lat = ne.lat + (row + 0.5) * scaleY
                if (lon < lonMin || lon > lonMax || lat < latMin || lat > latMax) continue
                val v = scan.valueAt(col, row)
                if (v.isNaN()) continue
                count++
                sum += v
                if (v < min) min = v.toDouble()
                if (v > max) max = v.toDouble()
            }
        }
        return Stats(count, sum, if (count > 0) sum / count else 0.0, min, max)
    }

    private fun analyzeRect(lonMin: Double, latMin: Double, lonMax: Double, latMax: Double): ScanAnalysis {
        val result = analysisService.analyzeRainfall(
            rectWkt(lonMin, latMin, lonMax, latMax),
            scan.header.time.minusMinutes(1),
            scan.header.time.plusMinutes(1),
        )
        assertThat(result.scans).hasSize(1)
        return result.scans[0]
    }

    @Test
    fun `interior sub-polygon stats match reference stats computed from the grid`() {
        val (sw, ne) = projectionConverter.gridBounds(scan.header)
        val lonSpan = ne.lon - sw.lon
        val latSpan = ne.lat - sw.lat
        // Interior 60% rectangle — clip must exclude the outer 20% margin on every side
        val r = listOf(sw.lon + 0.2 * lonSpan, sw.lat + 0.2 * latSpan, sw.lon + 0.8 * lonSpan, sw.lat + 0.8 * latSpan)

        val actual = analyzeRect(r[0], r[1], r[2], r[3])
        val expected = expectedStats(r[0], r[1], r[2], r[3])

        assertThat(actual.count).isEqualTo(expected.count)
        assertThat(actual.sum).isCloseTo(expected.sum, Offset.offset(0.5))
        assertThat(actual.mean).isCloseTo(expected.mean, Offset.offset(0.001))
        assertThat(actual.max).isCloseTo(expected.max, Offset.offset(0.01))
    }

    @Test
    fun `small mid-grid sub-polygon stats match reference stats computed from the grid`() {
        val (sw, ne) = projectionConverter.gridBounds(scan.header)
        val midLon = (sw.lon + ne.lon) / 2
        val midLat = (sw.lat + ne.lat) / 2
        val r = listOf(midLon - 0.2, midLat - 0.15, midLon + 0.2, midLat + 0.15)

        val actual = analyzeRect(r[0], r[1], r[2], r[3])
        val expected = expectedStats(r[0], r[1], r[2], r[3])

        assertThat(actual.count).isEqualTo(expected.count)
        assertThat(actual.sum).isCloseTo(expected.sum, Offset.offset(0.5))
        assertThat(actual.mean).isCloseTo(expected.mean, Offset.offset(0.001))
    }

    @Test
    fun `nested polygons clip strictly fewer pixels the smaller they get`() {
        val (sw, ne) = projectionConverter.gridBounds(scan.header)
        val lonSpan = ne.lon - sw.lon
        val latSpan = ne.lat - sw.lat

        val full = analyzeRect(sw.lon, sw.lat, ne.lon, ne.lat)
        val big = analyzeRect(sw.lon + 0.2 * lonSpan, sw.lat + 0.2 * latSpan, sw.lon + 0.8 * lonSpan, sw.lat + 0.8 * latSpan)
        val small = analyzeRect(sw.lon + 0.4 * lonSpan, sw.lat + 0.4 * latSpan, sw.lon + 0.6 * lonSpan, sw.lat + 0.6 * latSpan)

        assertThat(full.count).isEqualTo(scan.header.width.toLong() * scan.header.height)
        assertThat(big.count).isLessThan(full.count)
        assertThat(small.count).isLessThan(big.count)
        // A nested polygon can never see a higher max than its parent
        assertThat(big.max).isLessThanOrEqualTo(full.max)
        assertThat(small.max).isLessThanOrEqualTo(big.max)
    }

    @Test
    fun `repeated identical queries return identical results`() {
        val (sw, ne) = projectionConverter.gridBounds(scan.header)
        val midLon = (sw.lon + ne.lon) / 2
        val midLat = (sw.lat + ne.lat) / 2
        val wkt = rectWkt(midLon - 0.3, midLat - 0.2, midLon + 0.3, midLat + 0.2)
        val from = scan.header.time.minusMinutes(1)
        val to = scan.header.time.plusMinutes(1)

        val first = analysisService.analyzeRainfall(wkt, from, to)
        repeat(3) {
            val next = analysisService.analyzeRainfall(wkt, from, to)
            assertThat(next.scans).isEqualTo(first.scans)
            assertThat(next.accumulatedRainfallMm).isEqualTo(first.accumulatedRainfallMm)
        }
    }
}
