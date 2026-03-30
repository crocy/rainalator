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

    fun analyzeRainfall(polygonWkt: String, from: ZonedDateTime, to: ZonedDateTime): RainfallResult {
        val sql = """
            SELECT
                scan_time,
                (ST_SummaryStats(ST_Clip(raster_data, ST_GeomFromText(?, 4326), true), 1, true)).*
            FROM radar_scans
            WHERE scan_time >= ? AND scan_time <= ?
              AND ST_Intersects(bbox, ST_GeomFromText(?, 4326))
            ORDER BY scan_time
        """.trimIndent()

        val scans = mutableListOf<ScanAnalysis>()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, polygonWkt)
                stmt.setTimestamp(2, Timestamp.from(from.toInstant()))
                stmt.setTimestamp(3, Timestamp.from(to.toInstant()))
                stmt.setString(4, polygonWkt)

                val rs = stmt.executeQuery()
                while (rs.next()) {
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
        }

        val accumulatedMm = scans.sumOf { it.mean * (5.0 / 60.0) }
        return RainfallResult(scans = scans, accumulatedRainfallMm = accumulatedMm)
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
