package si.rainalator.ingestion

import io.quarkus.logging.Log
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import si.rainalator.config.AppConfig
import si.rainalator.storage.RasterStorageService
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@ApplicationScoped
class RadarDataScheduler {

    @Inject
    lateinit var appConfig: AppConfig

    @Inject
    lateinit var storageService: RasterStorageService

    private val parser = SrdParser()

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    @Scheduled(every = "{rainalator.fetch-interval-minutes}m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun fetchAndStore() {
        try {
            Log.info("Fetching radar data from ARSO...")
            val startTime = System.currentTimeMillis()

            val body = fetchSrdFile()
            val fetchTime = System.currentTimeMillis() - startTime

            val scan = parser.parse(body)
            val parseTime = System.currentTimeMillis() - startTime - fetchTime

            storageService.insert(scan)
            val storeTime = System.currentTimeMillis() - startTime - fetchTime - parseTime

            Log.infof(
                "Radar scan %s stored successfully (fetch=%dms, parse=%dms, store=%dms)",
                scan.header.time, fetchTime, parseTime, storeTime
            )
        } catch (e: Exception) {
            Log.errorf(e, "Failed to fetch/store radar data")
        }
    }

    private fun fetchSrdFile(): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(appConfig.arsoUrl()))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw RuntimeException("ARSO returned HTTP ${response.statusCode()}")
        }

        return response.body()
    }
}
