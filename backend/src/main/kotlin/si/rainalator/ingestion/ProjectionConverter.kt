package si.rainalator.ingestion

import org.locationtech.proj4j.CRSFactory
import org.locationtech.proj4j.CoordinateTransformFactory
import org.locationtech.proj4j.ProjCoordinate

/**
 * Converts SRD grid cell indices (col, row) to WGS84 (lon, lat)
 * using the Lambert Conformal Conic projection parameters from the SRD header.
 */
class ProjectionConverter {

    data class LatLon(val lat: Double, val lon: Double)

    private val crsFactory = CRSFactory()
    private val ctFactory = CoordinateTransformFactory()
    private val wgs84 = crsFactory.createFromParameters(
        "WGS84", "+proj=longlat +datum=WGS84 +no_defs"
    )

    fun gridToLatLon(col: Int, row: Int, header: SrdHeader): LatLon {
        // SRD shift defines the offset of the grid center from the projection origin.
        // Row 0 is the northernmost (top) row; data is written north-to-south.
        val xKm = header.shiftX + (col - header.width / 2.0) * header.cellSizeX
        val yKm = header.shiftY + (header.height / 2.0 - row) * header.cellSizeY

        val lcc = crsFactory.createFromParameters("ARSO_LCC", buildProj4String(header))
        val transform = ctFactory.createTransform(lcc, wgs84)

        val src = ProjCoordinate(xKm * 1000.0, yKm * 1000.0)
        val dst = ProjCoordinate()
        transform.transform(src, dst)

        return LatLon(lat = dst.y, lon = dst.x)
    }

    fun gridBounds(header: SrdHeader): Pair<LatLon, LatLon> {
        // Compute all four corners and find the bounding box
        val corners = listOf(
            gridToLatLon(0, 0, header),
            gridToLatLon(header.width - 1, 0, header),
            gridToLatLon(0, header.height - 1, header),
            gridToLatLon(header.width - 1, header.height - 1, header),
        )

        val sw = LatLon(
            lat = corners.minOf { it.lat },
            lon = corners.minOf { it.lon },
        )
        val ne = LatLon(
            lat = corners.maxOf { it.lat },
            lon = corners.maxOf { it.lon },
        )
        return Pair(sw, ne)
    }

    private fun buildProj4String(header: SrdHeader): String {
        return "+proj=lcc" +
            " +lat_1=${header.par1}" +
            " +lat_2=${header.par2}" +
            " +lat_0=${header.originLat}" +
            " +lon_0=${header.originLon}" +
            " +x_0=0 +y_0=0" +
            " +R=${header.ellipseA * 1000.0}" + // SRD uses km, proj4 uses meters
            " +units=m +no_defs"
    }
}
