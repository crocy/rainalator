package si.rainalator.ingestion

import io.quarkus.logging.Log
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.postgresql.util.PSQLException
import si.rainalator.config.AppConfig
import si.rainalator.storage.RasterStorageService
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration

@ApplicationScoped
class RadarDataScheduler {

    @Inject
    lateinit var appConfig: AppConfig

    @Inject
    lateinit var storageService: RasterStorageService

    @Inject
    lateinit var spillService: SpillService

    @Inject
    lateinit var archiveService: RawArchiveService

    private val parser = SrdParser()

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    @Scheduled(every = "{rainalator.fetch-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun fetchAndStore() {
        drainSpillDir()

        try {
            Log.info("Fetching radar data from ARSO...")
            val startTime = System.currentTimeMillis()

            val body = fetchSrdFile()
            val fetchTime = System.currentTimeMillis() - startTime

            val scan = parser.parse(body)
            val parseTime = System.currentTimeMillis() - startTime - fetchTime

            archiveService.saveRawSrd(scan.header.time, body)

            try {
                storageService.insert(scan)
                val storeTime = System.currentTimeMillis() - startTime - fetchTime - parseTime

                Log.infof(
                    "Radar scan %s stored successfully (fetch=%dms, parse=%dms, store=%dms)",
                    scan.header.time, fetchTime, parseTime, storeTime
                )
            } catch (e: Exception) {
                Log.errorf(e, "Failed to store radar data, spilling to disk")
                spillService.saveToSpillDir(scan.header.time, body)
            }
        } catch (e: Exception) {
            Log.errorf(e, "Failed to fetch/parse radar data")
        }
    }

    private fun drainSpillDir() {
        val pending = spillService.listPendingFiles()
        if (pending.isEmpty()) return

        Log.infof("Draining %d pending spill files...", pending.size)
        for (file in pending) {
            try {
                val body = Files.readString(file)
                val scan = parser.parse(body)
                storageService.insert(scan)
                spillService.deleteSpillFile(file)
                Log.infof("Drained spill file %s", file.fileName)
            } catch (e: PSQLException) {
                if (e.sqlState == "23505") {
                    spillService.deleteSpillFile(file)
                    Log.infof("Spill file %s already in DB (duplicate key), removed", file.fileName)
                } else {
                    Log.errorf(e, "Failed to drain spill file %s, stopping drain", file.fileName)
                    break
                }
            } catch (e: Exception) {
                Log.errorf(e, "Failed to drain spill file %s, stopping drain", file.fileName)
                break
            }
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
