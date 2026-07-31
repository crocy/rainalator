package si.rainalator.analysis

import jakarta.enterprise.context.ApplicationScoped
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.sql.Timestamp
import java.time.ZoneOffset
import java.time.ZonedDateTime
import javax.imageio.ImageIO
import javax.sql.DataSource

@ApplicationScoped
class RainfallAnalysisService(private val dataSource: DataSource) {

    private val colorScale = RainfallColorScale()

    private companion object {
        /** ARSO publishes one scan per 5 minutes; each scan's rate holds for that interval. */
        const val SCAN_INTERVAL_HOURS = 5.0 / 60.0
    }

    fun analyzeRainfall(polygonWkt: String, from: ZonedDateTime, to: ZonedDateTime): RainfallResult {
        // Pixel footprint is evaluated at the polygon centroid: the raster is stored on a
        // uniform degree grid, so a pixel's ground area shrinks with latitude.
        val sql = """
            SELECT
                scan_time,
                ST_Area(
                    ST_MakeEnvelope(
                        LEAST(ST_X(c.centroid), ST_X(c.centroid) + ST_ScaleX(raster_data)),
                        LEAST(ST_Y(c.centroid), ST_Y(c.centroid) + ST_ScaleY(raster_data)),
                        GREATEST(ST_X(c.centroid), ST_X(c.centroid) + ST_ScaleX(raster_data)),
                        GREATEST(ST_Y(c.centroid), ST_Y(c.centroid) + ST_ScaleY(raster_data)),
                        4326
                    )::geography
                ) AS pixel_area_m2,
                (ST_SummaryStats(ST_Clip(raster_data, p.geom, true), 1, true)).*
            FROM radar_scans,
                 (SELECT ST_GeomFromText(?, 4326) AS geom) p,
                 LATERAL (SELECT ST_Centroid(p.geom) AS centroid) c
            WHERE scan_time >= ? AND scan_time <= ?
              AND ST_Intersects(bbox, p.geom)
            ORDER BY scan_time
        """.trimIndent()

        val scans = mutableListOf<ScanAnalysis>()
        var pixelAreaM2 = 0.0
        val areaKm2: Double

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, polygonWkt)
                stmt.setTimestamp(2, Timestamp.from(from.toInstant()))
                stmt.setTimestamp(3, Timestamp.from(to.toInstant()))

                val rs = stmt.executeQuery()
                while (rs.next()) {
                    pixelAreaM2 = rs.getDouble("pixel_area_m2")
                    scans.add(
                        ScanAnalysis(
                            scanTime = rs.getTimestamp("scan_time").toInstant().atZone(ZoneOffset.UTC),
                            count = rs.getLong("count"),
                            sum = rs.getDouble("sum"),
                            mean = rs.getDouble("mean"),
                            stddev = rs.getDouble("stddev"),
                            min = rs.getDouble("min"),
                            max = rs.getDouble("max"),
                        )
                    )
                }
            }

            // Derived from the polygon alone so the selected area is reportable even with no scans.
            conn.prepareStatement("SELECT ST_Area(ST_GeomFromText(?, 4326)::geography) / 1e6").use { stmt ->
                stmt.setString(1, polygonWkt)
                val rs = stmt.executeQuery()
                rs.next()
                areaKm2 = rs.getDouble(1)
            }
        }

        val accumulatedMm = scans.sumOf { it.mean * SCAN_INTERVAL_HOURS }
        // sum is mm/h totalled over pixels; × hours × pixel area gives mm·m², and 1 mm·m² = 0.001 m³.
        val totalVolumeM3 = scans.sumOf { it.sum * SCAN_INTERVAL_HOURS } * pixelAreaM2 / 1000.0

        return RainfallResult(
            scans = scans,
            accumulatedRainfallMm = accumulatedMm,
            totalVolumeM3 = totalVolumeM3,
            areaKm2 = areaKm2,
        )
    }

    fun renderOverlayPng(scanTime: ZonedDateTime): OverlayImage? {
        val metaSql = """
            SELECT
                ST_Width(raster_data) as width,
                ST_Height(raster_data) as height,
                ST_UpperLeftX(raster_data) as upper_left_x,
                ST_UpperLeftY(raster_data) as upper_left_y,
                ST_ScaleX(raster_data) as scale_x,
                ST_ScaleY(raster_data) as scale_y
            FROM radar_scans
            WHERE scan_time = ?
        """.trimIndent()

        val pixelSql = """
            SELECT ST_DumpValues(raster_data, 1) as pixel_values
            FROM radar_scans
            WHERE scan_time = ?
        """.trimIndent()

        dataSource.connection.use { conn ->
            // First get raster metadata
            val width: Int
            val height: Int
            val upperLeftX: Double
            val upperLeftY: Double
            val scaleX: Double
            val scaleY: Double

            conn.prepareStatement(metaSql).use { stmt ->
                stmt.setTimestamp(1, Timestamp.from(scanTime.toInstant()))
                val rs = stmt.executeQuery()
                if (!rs.next()) return null
                width = rs.getInt("width")
                height = rs.getInt("height")
                upperLeftX = rs.getDouble("upper_left_x")
                upperLeftY = rs.getDouble("upper_left_y")
                scaleX = rs.getDouble("scale_x")
                scaleY = rs.getDouble("scale_y")
            }

            // ST_DumpValues returns double precision[][] (rows × cols)
            val pixelValues: DoubleArray
            conn.prepareStatement(pixelSql).use { stmt ->
                stmt.setTimestamp(1, Timestamp.from(scanTime.toInstant()))
                val rs = stmt.executeQuery()
                if (!rs.next()) return null
                val sqlArray = rs.getArray("pixel_values")
                val rows = sqlArray.array as Array<*>
                val flat = mutableListOf<Double>()
                for (row in rows) {
                    val cols = row as Array<*>
                    for (v in cols) {
                        flat.add((v as? Number)?.toDouble() ?: Double.NaN)
                    }
                }
                pixelValues = flat.toDoubleArray()
            }

            val bounds = GeoBounds(
                west = upperLeftX,
                north = upperLeftY,
                east = upperLeftX + width * scaleX,
                south = upperLeftY + height * scaleY, // scaleY is negative, so this is south
            )

            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val idx = y * width + x
                    val value = if (idx < pixelValues.size) pixelValues[idx] else Double.NaN
                    image.setRGB(x, y, colorScale.toArgb(value))
                }
            }

            val baos = ByteArrayOutputStream()
            ImageIO.write(image, "PNG", baos)

            return OverlayImage(pngBytes = baos.toByteArray(), bounds = bounds)
        }
    }
}
