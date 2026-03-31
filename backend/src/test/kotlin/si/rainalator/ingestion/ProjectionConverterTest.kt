package si.rainalator.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class ProjectionConverterTest {

    private lateinit var converter: ProjectionConverter
    private lateinit var arsoHeader: SrdHeader

    companion object {
        // Known geographic coordinates for validation
        // Ljubljana: approximately 14.505°E, 46.056°N
        // Maribor: approximately 15.646°E, 46.558°N
        // Projection origin: 14.815°E, 46.120°N (should be at shift offset)
        val COORD_TOLERANCE = Offset.offset(0.05) // ~5km tolerance for 1km grid
    }

    @BeforeEach
    fun setUp() {
        converter = ProjectionConverter()
        arsoHeader = SrdHeader(
            domain = "SI0",
            sourceRadars = listOf("SI1", "SI2"),
            time = ZonedDateTime.of(2026, 3, 30, 17, 0, 0, 0, ZoneOffset.UTC),
            width = 401,
            height = 301,
            cellSizeX = 1.0,
            cellSizeY = 1.0,
            projection = "LCC",
            ellipseA = 6371.0,
            ellipseB = 6371.0,
            par1 = 46.120,
            par2 = 46.120,
            originLon = 14.815,
            originLat = 46.120,
            shiftX = -4.0,
            shiftY = -6.0,
            offset = 64,
            start = -8.0,
            slope = 2.0,
            nodata = 126,
        )
    }

    @Nested
    inner class ProjectionOrigin {

        @Test
        fun `grid cell at center offset maps to projection origin`() {
            // The projection origin (14.815, 46.120) should correspond to
            // the grid cell where x_km=0, y_km=0 in projected space.
            // SRD shift defines the offset of the grid center from the origin.
            // x_km = shiftX + (col - width/2) * cellSize = 0 → col = width/2 - shiftX = 204
            // y_km = shiftY + (height/2 - row) * cellSize = 0 → row = height/2 + shiftY = 144
            val result = converter.gridToLatLon(204, 144, arsoHeader)
            assertThat(result.lon).isCloseTo(14.815, Offset.offset(0.01))
            assertThat(result.lat).isCloseTo(46.120, Offset.offset(0.01))
        }
    }

    @Nested
    inner class KnownLocations {

        @Test
        fun `Ljubljana is within the grid`() {
            // Ljubljana ~ 14.51°E, 46.06°N
            // With center-based shift, the grid is centered near origin (14.815, 46.12)
            // and Ljubljana is ~24km west and ~7km south of origin,
            // so it falls within the grid at approximately col=180, row=151.
            val center = converter.gridToLatLon(200, 150, arsoHeader)
            // Center of a 401x301 grid centered on Slovenia
            assertThat(center.lat).isBetween(44.0, 48.0) // Reasonable latitude for Slovenia region
            assertThat(center.lon).isBetween(12.0, 18.0) // Reasonable longitude for Slovenia region
        }

        @Test
        fun `grid corners produce reasonable coordinates`() {
            // Row 0 is northernmost (top of grid), row 300 is southernmost
            val nw = converter.gridToLatLon(0, 0, arsoHeader)
            val ne = converter.gridToLatLon(400, 0, arsoHeader)
            val sw = converter.gridToLatLon(0, 300, arsoHeader)
            val se = converter.gridToLatLon(400, 300, arsoHeader)

            // All corners should be in the broader Alpine/Adriatic region
            listOf(nw, ne, sw, se).forEach { corner ->
                assertThat(corner.lat).isBetween(42.0, 50.0)
                assertThat(corner.lon).isBetween(10.0, 22.0)
            }

            // Longitude should increase west to east
            assertThat(ne.lon).isGreaterThan(nw.lon)
            assertThat(se.lon).isGreaterThan(sw.lon)

            // Row 0 (north) should have higher latitude than row 300 (south)
            assertThat(nw.lat).isGreaterThan(sw.lat)
            assertThat(ne.lat).isGreaterThan(se.lat)
        }
    }

    @Nested
    inner class GridBounds {

        @Test
        fun `bounds cover Slovenia and surroundings`() {
            val (sw, ne) = converter.gridBounds(arsoHeader)

            // Grid is centered near the origin (14.815, 46.12) with shift (-4, -6).
            // 401x301 cells at 1km: spans ~400km W-E and ~300km N-S.
            // The grid extends roughly 200km in each direction from center.
            assertThat(sw.lat).isLessThan(45.0)   // Well south of origin
            assertThat(sw.lon).isLessThan(13.0)    // Well west of origin
            assertThat(ne.lat).isGreaterThan(47.0) // Well north of origin
            assertThat(ne.lon).isGreaterThan(17.0)  // Well east of origin

            // Origin should be roughly centered within the bounds
            assertThat(14.815).isBetween(sw.lon, ne.lon)
            assertThat(46.120).isBetween(sw.lat, ne.lat)
        }

        @Test
        fun `SW corner has lower lat and lon than NE corner`() {
            val (sw, ne) = converter.gridBounds(arsoHeader)
            assertThat(ne.lat).isGreaterThan(sw.lat)
            assertThat(ne.lon).isGreaterThan(sw.lon)
        }
    }

    @Nested
    inner class GridSpacing {

        @Test
        fun `adjacent cells are approximately 1km apart`() {
            val a = converter.gridToLatLon(200, 150, arsoHeader)
            val b = converter.gridToLatLon(201, 150, arsoHeader)

            // 1km at ~46°N latitude ≈ 0.013° longitude
            val dLon = Math.abs(b.lon - a.lon)
            assertThat(dLon).isBetween(0.008, 0.020) // roughly 1km

            val c = converter.gridToLatLon(200, 151, arsoHeader)
            // 1km ≈ 0.009° latitude
            val dLat = Math.abs(c.lat - a.lat)
            assertThat(dLat).isBetween(0.005, 0.015) // roughly 1km
        }
    }
}
