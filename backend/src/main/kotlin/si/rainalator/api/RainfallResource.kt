package si.rainalator.api

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import si.rainalator.analysis.RainfallAnalysisService
import si.rainalator.api.dto.RainfallQueryRequest
import si.rainalator.api.dto.RainfallQueryResponse
import si.rainalator.api.dto.ScanStats
import si.rainalator.api.dto.ScanTimesResponse
import si.rainalator.storage.RadarScanRepository
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

@Path("/api/rainfall")
@ApplicationScoped
class RainfallResource(
    private val analysisService: RainfallAnalysisService,
    private val scanRepository: RadarScanRepository,
) {

    @POST
    @Path("/query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun queryRainfall(request: RainfallQueryRequest): Response {
        if (request.polygon.isBlank()) {
            return Response.status(400).entity(mapOf("error" to "polygon is required")).build()
        }

        val from: ZonedDateTime
        val to: ZonedDateTime
        try {
            from = ZonedDateTime.parse(request.from)
            to = ZonedDateTime.parse(request.to)
        } catch (e: DateTimeParseException) {
            return Response.status(400).entity(mapOf("error" to "Invalid date format: ${e.message}")).build()
        }

        if (from.isAfter(to)) {
            return Response.status(400).entity(mapOf("error" to "from must be before to")).build()
        }

        val result = analysisService.analyzeRainfall(request.polygon, from, to)

        val response = RainfallQueryResponse(
            scans = result.scans.map { scan ->
                ScanStats(
                    scanTime = scan.scanTime.toString(),
                    mean = scan.mean,
                    min = scan.min,
                    max = scan.max,
                    sum = scan.sum,
                    count = scan.count,
                )
            },
            accumulatedRainfallMm = result.accumulatedRainfallMm,
            totalVolumeM3 = result.totalVolumeM3,
            areaKm2 = result.areaKm2,
            scanCount = result.scans.size,
        )

        return Response.ok(response).build()
    }

    @GET
    @Path("/scans")
    @Produces(MediaType.APPLICATION_JSON)
    fun getScanTimes(
        @QueryParam("from") fromStr: String,
        @QueryParam("to") toStr: String,
    ): Response {
        val from: ZonedDateTime
        val to: ZonedDateTime
        try {
            from = ZonedDateTime.parse(fromStr)
            to = ZonedDateTime.parse(toStr)
        } catch (e: DateTimeParseException) {
            return Response.status(400).entity(mapOf("error" to "Invalid date format: ${e.message}")).build()
        }

        val times = scanRepository.findScanTimes(from, to)
        val response = ScanTimesResponse(
            scanTimes = times.map { it.toString() },
            count = times.size,
        )

        return Response.ok(response).build()
    }

    @GET
    @Path("/overlay/{timestamp}")
    @Produces("image/png")
    fun getOverlay(@PathParam("timestamp") timestampStr: String): Response {
        val scanTime: ZonedDateTime
        try {
            scanTime = ZonedDateTime.parse(timestampStr)
        } catch (e: DateTimeParseException) {
            return Response.status(400).entity("Invalid timestamp format".toByteArray()).build()
        }

        val overlay = analysisService.renderOverlayPng(scanTime)
            ?: return Response.status(404).entity("No scan at this timestamp".toByteArray()).build()

        return Response.ok(overlay.pngBytes)
            .type("image/png")
            .header("X-Bounds-South", overlay.bounds.south.toString())
            .header("X-Bounds-West", overlay.bounds.west.toString())
            .header("X-Bounds-North", overlay.bounds.north.toString())
            .header("X-Bounds-East", overlay.bounds.east.toString())
            .build()
    }
}
