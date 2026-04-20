package si.rainalator.ingestion

import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import si.rainalator.config.AppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

@ApplicationScoped
class RawArchiveService(val archiveDir: Path) {

    @Inject
    constructor(appConfig: AppConfig) : this(Path.of(appConfig.rawArchiveDir()))

    init {
        Files.createDirectories(archiveDir)
    }

    fun saveRawSrd(scanTime: ZonedDateTime, rawBody: String) {
        val date = scanTime.withZoneSameInstant(ZoneOffset.UTC).toLocalDate()
        val dateDir = archiveDir.resolve(date.toString())
        Files.createDirectories(dateDir)

        val filename = scanTime.toInstant().toString().replace(":", "-") + ".srd"
        val path = dateDir.resolve(filename)
        Files.writeString(path, rawBody)
        Log.infof("Archived raw SRD for %s to %s", scanTime, path)
    }

    fun getDateDir(date: LocalDate): Path = archiveDir.resolve(date.toString())
}
