package si.rainalator.storage

import si.rainalator.ingestion.ProjectionConverter
import si.rainalator.ingestion.RadarScan
import java.sql.Connection
import java.sql.Timestamp
import java.time.ZoneOffset
import java.time.ZonedDateTime
import javax.sql.DataSource

/**
 * Stores radar scans as PostGIS rasters.
 * Uses ST_MakeEmptyRaster + ST_AddBand + ST_SetValues to build rasters in SQL.
 */
class RasterStorageService(private val dataSource: DataSource) : RadarScanRepository {

    private val projectionConverter = ProjectionConverter()

    override fun insert(scan: RadarScan) {
        val header = scan.header
        val (sw, ne) = projectionConverter.gridBounds(header)

        // Compute raster georeferencing in WGS84
        val upperLeftX = sw.lon
        val upperLeftY = ne.lat
        val scaleX = (ne.lon - sw.lon) / header.width
        val scaleY = -(ne.lat - sw.lat) / header.height // negative: raster goes top-down

        dataSource.connection.use { conn ->
            // Build the 2D array string for ST_SetValues
            val arrayStr = buildPostgresArray(scan)

            val sql = """
                INSERT INTO radar_scans (scan_time, source_radars, raster_data, bbox)
                VALUES (
                    ?,
                    ?,
                    ST_SetValues(
                        ST_AddBand(
                            ST_MakeEmptyRaster(?, ?, ?::float8, ?::float8, ?::float8, ?::float8, 0, 0, 4326),
                            '32BF'::text,
                            0,
                            'NaN'::double precision
                        ),
                        1, 1, 1,
                        $arrayStr
                    ),
                    ST_MakeEnvelope(?, ?, ?, ?, 4326)
                )
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setTimestamp(1, Timestamp.from(header.time.toInstant()))
                stmt.setArray(2, conn.createArrayOf("text", header.sourceRadars.toTypedArray()))
                stmt.setInt(3, header.width)    // raster width
                stmt.setInt(4, header.height)   // raster height
                stmt.setDouble(5, upperLeftX)   // upper left X
                stmt.setDouble(6, upperLeftY)   // upper left Y
                stmt.setDouble(7, scaleX)       // pixel scale X
                stmt.setDouble(8, scaleY)       // pixel scale Y (negative)
                stmt.setDouble(9, sw.lon)       // envelope minX
                stmt.setDouble(10, sw.lat)      // envelope minY
                stmt.setDouble(11, ne.lon)      // envelope maxX
                stmt.setDouble(12, ne.lat)      // envelope maxY
                stmt.executeUpdate()
            }
        }
    }

    override fun findScanTimes(from: ZonedDateTime, to: ZonedDateTime): List<ZonedDateTime> {
        val sql = "SELECT scan_time FROM radar_scans WHERE scan_time >= ? AND scan_time <= ? ORDER BY scan_time"
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setTimestamp(1, Timestamp.from(from.toInstant()))
                stmt.setTimestamp(2, Timestamp.from(to.toInstant()))
                val rs = stmt.executeQuery()
                val times = mutableListOf<ZonedDateTime>()
                while (rs.next()) {
                    times.add(rs.getTimestamp("scan_time").toInstant().atZone(ZoneOffset.UTC))
                }
                return times
            }
        }
    }

    override fun countScans(): Long {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT COUNT(*) FROM radar_scans")
                rs.next()
                return rs.getLong(1)
            }
        }
    }

    /**
     * Builds a PostgreSQL 2D array literal from scan values.
     * Format: ARRAY[ARRAY[v1,v2,...], ARRAY[v3,v4,...], ...]::double precision[][]
     * NaN values become 'NaN' which PostGIS treats as nodata.
     */
    private fun buildPostgresArray(scan: RadarScan): String {
        val header = scan.header
        val sb = StringBuilder("ARRAY[")

        for (row in 0 until header.height) {
            if (row > 0) sb.append(',')
            sb.append("ARRAY[")
            for (col in 0 until header.width) {
                if (col > 0) sb.append(',')
                val v = scan.valueAt(col, row)
                if (v.isNaN()) {
                    sb.append("'NaN'::double precision")
                } else {
                    sb.append(v.toDouble())
                }
            }
            sb.append(']')
        }

        sb.append("]::double precision[][]")
        return sb.toString()
    }
}
