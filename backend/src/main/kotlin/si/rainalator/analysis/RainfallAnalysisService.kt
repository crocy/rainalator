package si.rainalator.analysis

import jakarta.enterprise.context.ApplicationScoped
import si.rainalator.storage.RadarScanRepository
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime
import javax.imageio.ImageIO
import javax.sql.DataSource

@ApplicationScoped
class RainfallAnalysisService(
    private val dataSource: DataSource,
    private val scanRepository: RadarScanRepository,
) {

    private val colorScale = RainfallColorScale()

    private companion object {
        /** ARSO publishes one scan per 5 minutes; each scan's rate holds for that interval. */
        const val SCAN_INTERVAL_MINUTES = 5
        const val SCAN_INTERVAL_HOURS = SCAN_INTERVAL_MINUTES / 60.0
    }

    fun analyzeRainfall(
        polygonWkt: String,
        from: ZonedDateTime,
        to: ZonedDateTime,
        bucketMinutes: Int? = null,
    ): RainfallResult {
        validatePeriod(from, to)
        bucketMinutes?.let { validateBucketMinutes(it, from, to) }

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
            WHERE scan_time >= ? AND scan_time < ?
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
            buckets = foldBuckets(scans, from, to, bucketMinutes, pixelAreaM2),
            accumulatedRainfallMm = accumulatedMm,
            totalVolumeM3 = totalVolumeM3,
            areaKm2 = areaKm2,
            bucketMinutes = bucketMinutes ?: SCAN_INTERVAL_MINUTES,
        )
    }

    fun listFrames(
        from: ZonedDateTime,
        to: ZonedDateTime,
        bucketMinutes: Int?,
        scale: ColorScaleMode?,
    ): FrameList {
        validatePeriod(from, to)
        bucketMinutes?.let { validateBucketMinutes(it, from, to) }
        // Un-bucketed autofit would union every scan separately; bucketing bounds that work.
        if (scale == ColorScaleMode.AUTOFIT && bucketMinutes == null) {
            throw InvalidQueryException("bucketMinutes is required for autofit")
        }

        val frames = if (bucketMinutes == null) {
            scanRepository.findScanTimes(from, to).map { scanTime ->
                Frame(scanTime.toUtc(), scanTime.plusMinutes(SCAN_INTERVAL_MINUTES.toLong()).toUtc(), partial = false)
            }
        } else {
            bucketWindows(from, to, bucketMinutes).map { Frame(it.start, it.end, it.partial) }
        }

        // Front-loaded so every frame in the period shares one scale and stays comparable;
        // rendering re-unions per frame, and the duplicated raster work only lands on autofit.
        val scaleMaxMm = if (scale == ColorScaleMode.AUTOFIT) {
            val deepest = dataSource.connection.use { conn ->
                frames.maxOfOrNull { maxDepthMm(conn, it.start, it.end) } ?: 0.0
            }
            // Floored at the lowest band so a dry period still yields a maximum the overlay accepts;
            // it then paints fully transparent, which is the honest picture.
            maxOf(deepest, RainfallColorScale.DEPTH_THRESHOLDS.first())
        } else {
            null
        }

        return FrameList(
            frames = frames,
            bucketMinutes = bucketMinutes ?: SCAN_INTERVAL_MINUTES,
            scaleMaxMm = scaleMaxMm,
        )
    }

    fun renderOverlayPng(
        start: ZonedDateTime,
        end: ZonedDateTime,
        scale: ColorScaleMode,
        scaleMax: Double? = null,
    ): OverlayImage? {
        if (!start.isBefore(end)) throw InvalidQueryException("start must be before end")
        if (scale == ColorScaleMode.AUTOFIT && (scaleMax == null || scaleMax <= 0.0)) {
            throw InvalidQueryException("scaleMax is required for autofit")
        }

        val overlayScale = colorScale.overlayScale(scale, hoursBetween(start, end), scaleMax)

        // Summing the band across the frame's scans and scaling by the scan interval turns
        // mm/h per scan into the mm depth the frame collected.
        val sql = """
            SELECT
                ST_Width(u.rast) AS width,
                ST_Height(u.rast) AS height,
                ST_UpperLeftX(u.rast) AS upper_left_x,
                ST_UpperLeftY(u.rast) AS upper_left_y,
                ST_ScaleX(u.rast) AS scale_x,
                ST_ScaleY(u.rast) AS scale_y,
                ST_DumpValues(u.rast, 1) AS pixel_values
            FROM (
                SELECT ST_Union(raster_data, 'SUM') AS rast
                FROM radar_scans
                WHERE scan_time >= ? AND scan_time < ?
            ) u
            WHERE u.rast IS NOT NULL
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setTimestamp(1, Timestamp.from(start.toInstant()))
                stmt.setTimestamp(2, Timestamp.from(end.toInstant()))

                val rs = stmt.executeQuery()
                if (!rs.next()) return null

                val width = rs.getInt("width")
                val height = rs.getInt("height")
                val upperLeftX = rs.getDouble("upper_left_x")
                val upperLeftY = rs.getDouble("upper_left_y")
                val scaleX = rs.getDouble("scale_x")
                val scaleY = rs.getDouble("scale_y")
                val pixelValues = flattenPixelValues(rs.getArray("pixel_values"))

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
                        val rateSum = if (idx < pixelValues.size) pixelValues[idx] else Double.NaN
                        image.setRGB(x, y, overlayScale.toArgb(rateSum * SCAN_INTERVAL_HOURS))
                    }
                }

                val baos = ByteArrayOutputStream()
                ImageIO.write(image, "PNG", baos)

                return OverlayImage(
                    pngBytes = baos.toByteArray(),
                    bounds = bounds,
                    legendUnit = overlayScale.unit,
                    legendThresholds = overlayScale.legendThresholds,
                    legendColorsHex = overlayScale.legendColorsHex(),
                )
            }
        }
    }

    private fun foldBuckets(
        scans: List<ScanAnalysis>,
        from: ZonedDateTime,
        to: ZonedDateTime,
        bucketMinutes: Int?,
        pixelAreaM2: Double,
    ): List<RainfallBucket> {
        if (bucketMinutes == null) {
            return scans.map { scan ->
                val window = BucketWindow(
                    start = scan.scanTime.toUtc(),
                    end = scan.scanTime.plusMinutes(SCAN_INTERVAL_MINUTES.toLong()).toUtc(),
                    partial = false,
                )
                foldBucket(window, listOf(scan), pixelAreaM2)
            }
        }

        return bucketWindows(from, to, bucketMinutes).map { window ->
            foldBucket(window, scansIn(window, scans), pixelAreaM2)
        }
    }

    /**
     * A scan carries the rate for the interval *starting* at its timestamp, so membership is
     * half-open everywhere — period and bucket alike. Bucket membership therefore partitions
     * window membership exactly, and the totals cannot drift from the per-bucket figures.
     */
    private fun scansIn(window: BucketWindow, scans: List<ScanAnalysis>): List<ScanAnalysis> =
        scans.filter { !it.scanTime.isBefore(window.start) && it.scanTime.isBefore(window.end) }

    private fun foldBucket(
        window: BucketWindow,
        scans: List<ScanAnalysis>,
        pixelAreaM2: Double,
    ): RainfallBucket {
        val depthMm = scans.sumOf { it.mean * SCAN_INTERVAL_HOURS }
        val spanHours = hoursBetween(window.start, window.end)
        return RainfallBucket(
            start = window.start,
            end = window.end,
            depthMm = depthMm,
            meanRateMmH = if (spanHours > 0.0) depthMm / spanHours else 0.0,
            minRateMmH = scans.minOfOrNull { it.min } ?: 0.0,
            maxRateMmH = scans.maxOfOrNull { it.max } ?: 0.0,
            volumeM3 = scans.sumOf { it.sum * SCAN_INTERVAL_HOURS } * pixelAreaM2 / 1000.0,
            scanCount = scans.size,
            partial = window.partial,
        )
    }

    /** Deepest pixel of one frame over the whole raster — the overlay paints it all, not just the polygon. */
    private fun maxDepthMm(conn: Connection, start: ZonedDateTime, end: ZonedDateTime): Double {
        val sql = """
            SELECT (ST_SummaryStats(u.rast, 1, true)).max AS max_rate
            FROM (
                SELECT ST_Union(raster_data, 'SUM') AS rast
                FROM radar_scans
                WHERE scan_time >= ? AND scan_time < ?
            ) u
            WHERE u.rast IS NOT NULL
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setTimestamp(1, Timestamp.from(start.toInstant()))
            stmt.setTimestamp(2, Timestamp.from(end.toInstant()))
            val rs = stmt.executeQuery()
            if (!rs.next()) return 0.0
            val maxRate = rs.getDouble("max_rate")
            return if (rs.wasNull() || maxRate.isNaN()) 0.0 else maxRate * SCAN_INTERVAL_HOURS
        }
    }

    /** ST_DumpValues returns double precision[][] (rows × cols); nodata pixels arrive as NULL. */
    private fun flattenPixelValues(sqlArray: java.sql.Array): DoubleArray {
        val rows = sqlArray.array as Array<*>
        val flat = mutableListOf<Double>()
        for (row in rows) {
            val cols = row as Array<*>
            for (v in cols) {
                flat.add((v as? Number)?.toDouble() ?: Double.NaN)
            }
        }
        return flat.toDoubleArray()
    }

    private fun validatePeriod(from: ZonedDateTime, to: ZonedDateTime) {
        if (from.isAfter(to)) throw InvalidQueryException("from must be before to")
    }

    private fun validateBucketMinutes(bucketMinutes: Int, from: ZonedDateTime, to: ZonedDateTime) {
        if (bucketMinutes < SCAN_INTERVAL_MINUTES) {
            throw InvalidQueryException("bucketMinutes must be at least $SCAN_INTERVAL_MINUTES")
        }
        // A bucket that is not a whole number of scans would catch 1 or 2 scans alternately,
        // and the resulting sawtooth reads as weather rather than as an artefact.
        if (bucketMinutes % SCAN_INTERVAL_MINUTES != 0) {
            throw InvalidQueryException("bucketMinutes must be a multiple of $SCAN_INTERVAL_MINUTES")
        }
        if (Duration.ofMinutes(bucketMinutes.toLong()) > Duration.between(from, to)) {
            throw InvalidQueryException("bucketMinutes must not exceed the selected period")
        }
    }
}
