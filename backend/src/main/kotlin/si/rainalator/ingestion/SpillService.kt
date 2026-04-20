package si.rainalator.ingestion

import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import si.rainalator.config.AppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZonedDateTime

@ApplicationScoped
class SpillService(val spillDir: Path) {

    @Inject
    constructor(appConfig: AppConfig) : this(Path.of(appConfig.spillDir()))

    init {
        Files.createDirectories(spillDir)
    }

    fun saveToSpillDir(scanTime: ZonedDateTime, rawBody: String) {
        val filename = scanTime.toInstant().toString().replace(":", "-") + ".srd"
        val path = spillDir.resolve(filename)
        Files.writeString(path, rawBody)
        Log.infof("Spilled SRD data for %s to %s", scanTime, path)
    }

    fun listPendingFiles(): List<Path> {
        if (!Files.exists(spillDir)) return emptyList()
        return Files.list(spillDir).use { stream ->
            stream.filter { it.toString().endsWith(".srd") }
                .sorted()
                .toList()
        }
    }

    fun deleteSpillFile(path: Path) {
        Files.deleteIfExists(path)
    }

    fun pendingCount(): Int = listPendingFiles().size
}
