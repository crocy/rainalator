package si.rainalator.api

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import si.rainalator.analysis.ColorScaleMode
import si.rainalator.analysis.InvalidQueryException
import si.rainalator.analysis.RainfallAnalysisService
import si.rainalator.api.dto.BucketStats
import si.rainalator.api.dto.FrameStats
import si.rainalator.api.dto.FramesResponse
import si.rainalator.api.dto.RainfallQueryRequest
import si.rainalator.api.dto.RainfallQueryResponse
import java.math.BigDecimal
import java.math.MathContext
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

@Path("/api/rainfall")
@ApplicationScoped
class RainfallResource(private val analysisService: RainfallAnalysisService) {

    @POST
    @Path("/query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun queryRainfall(request: RainfallQueryRequest): Response {
        if (request.polygon.isBlank()) {
            return badRequest("polygon is required")
        }

        val from: ZonedDateTime
        val to: ZonedDateTime
        try {
            from = ZonedDateTime.parse(request.from)
            to = ZonedDateTime.parse(request.to)
        } catch (e: DateTimeParseException) {
            return badRequest("Invalid date format: ${e.message}")
        }

        val result = try {
            analysisService.analyzeRainfall(request.polygon, from, to, request.bucketMinutes)
        } catch (e: InvalidQueryException) {
            return badRequest(e.message)
        }

        val response = RainfallQueryResponse(
            buckets = result.buckets.map { bucket ->
                BucketStats(
                    start = bucket.start.toString(),
                    end = bucket.end.toString(),
                    depthMm = bucket.depthMm,
                    meanRateMmH = bucket.meanRateMmH,
                    minRateMmH = bucket.minRateMmH,
                    maxRateMmH = bucket.maxRateMmH,
                    volumeM3 = bucket.volumeM3,
                    scanCount = bucket.scanCount,
                    partial = bucket.partial,
                )
            },
            accumulatedRainfallMm = result.accumulatedRainfallMm,
            totalVolumeM3 = result.totalVolumeM3,
            areaKm2 = result.areaKm2,
            bucketCount = result.buckets.size,
            bucketMinutes = result.bucketMinutes,
        )

        return Response.ok(response).build()
    }

    @GET
    @Path("/frames")
    @Produces(MediaType.APPLICATION_JSON)
    fun getFrames(
        @QueryParam("from") fromStr: String?,
        @QueryParam("to") toStr: String?,
        @QueryParam("bucketMinutes") bucketMinutesStr: String?,
        @QueryParam("scale") scaleStr: String?,
    ): Response {
        if (fromStr == null || toStr == null) {
            return badRequest("from and to are required")
        }

        val from: ZonedDateTime
        val to: ZonedDateTime
        try {
            from = ZonedDateTime.parse(fromStr)
            to = ZonedDateTime.parse(toStr)
        } catch (e: DateTimeParseException) {
            return badRequest("Invalid date format: ${e.message}")
        }

        val bucketMinutes = bucketMinutesStr?.toIntOrNull()
        if (bucketMinutesStr != null && bucketMinutes == null) {
            return badRequest("bucketMinutes must be a whole number of minutes")
        }

        val scale = scaleStr?.let { ColorScaleMode.parse(it) }
        if (scaleStr != null && scale == null) {
            return badRequest(UNKNOWN_SCALE)
        }

        val frameList = try {
            analysisService.listFrames(from, to, bucketMinutes, scale)
        } catch (e: InvalidQueryException) {
            return badRequest(e.message)
        }

        val response = FramesResponse(
            frames = frameList.frames.map { FrameStats(it.start.toString(), it.end.toString(), it.partial) },
            count = frameList.frames.size,
            bucketMinutes = frameList.bucketMinutes,
            scaleMaxMm = frameList.scaleMaxMm,
        )

        return Response.ok(response).build()
    }

    @GET
    @Path("/overlay")
    @Produces("image/png", MediaType.APPLICATION_JSON)
    fun getOverlay(
        @QueryParam("start") startStr: String?,
        @QueryParam("end") endStr: String?,
        @QueryParam("scale") scaleStr: String?,
        @QueryParam("scaleMax") scaleMaxStr: String?,
    ): Response {
        if (startStr == null || endStr == null) {
            return badRequest("start and end are required")
        }

        val start: ZonedDateTime
        val end: ZonedDateTime
        try {
            start = ZonedDateTime.parse(startStr)
            end = ZonedDateTime.parse(endStr)
        } catch (e: DateTimeParseException) {
            return badRequest("Invalid date format: ${e.message}")
        }

        val scale = ColorScaleMode.parse(scaleStr ?: ColorScaleMode.RATE.name)
            ?: return badRequest(UNKNOWN_SCALE)

        val scaleMax = scaleMaxStr?.toDoubleOrNull()
        if (scaleMaxStr != null && scaleMax == null) {
            return badRequest("scaleMax must be a number")
        }

        val overlay = try {
            analysisService.renderOverlayPng(start, end, scale, scaleMax)
        } catch (e: InvalidQueryException) {
            return badRequest(e.message)
        } ?: return errorResponse(404, "No scan in this period")

        return Response.ok(overlay.pngBytes)
            .type("image/png")
            .header("X-Bounds-South", overlay.bounds.south.toString())
            .header("X-Bounds-West", overlay.bounds.west.toString())
            .header("X-Bounds-North", overlay.bounds.north.toString())
            .header("X-Bounds-East", overlay.bounds.east.toString())
            .header("X-Legend-Unit", overlay.legendUnit)
            .header("X-Legend-Thresholds", overlay.legendThresholds.joinToString(",", transform = ::formatThreshold))
            .header("X-Legend-Colors", overlay.legendColorsHex.joinToString(","))
            .build()
    }

    private fun badRequest(message: String): Response = errorResponse(400, message)

    private fun errorResponse(status: Int, message: String): Response =
        Response.status(status)
            .type(MediaType.APPLICATION_JSON)
            .entity(mapOf("error" to message))
            .build()

    /**
     * Keeps legend labels readable: 1 rather than 1.0, and 0.0825 rather than the 0.08249999999999999
     * that rescaling leaves behind. Only the label is rounded — the painter uses the full precision.
     */
    private fun formatThreshold(value: Double): String =
        BigDecimal.valueOf(value).round(LEGEND_PRECISION).stripTrailingZeros().toPlainString()

    private companion object {
        const val UNKNOWN_SCALE = "scale must be one of rate, fixed, autofit"

        /** Wide enough to keep every literal band edge intact (158.49), narrow enough to drop float noise. */
        val LEGEND_PRECISION = MathContext(6)
    }
}
