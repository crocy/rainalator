package si.rainalator.ingestion

import io.quarkus.logging.Log
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import si.rainalator.config.AppConfig
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.GZIPOutputStream

@ApplicationScoped
class S3ArchivalScheduler {

    private val s3Client: S3Client
    private val archiveService: RawArchiveService
    private val s3Bucket: String
    private val s3Prefix: String

    @Inject
    constructor(appConfig: AppConfig, s3Client: S3Client, archiveService: RawArchiveService) {
        this.s3Client = s3Client
        this.archiveService = archiveService
        this.s3Bucket = appConfig.s3Bucket()
        this.s3Prefix = appConfig.s3Prefix()
    }

    constructor(s3Client: S3Client, archiveService: RawArchiveService, s3Bucket: String, s3Prefix: String) {
        this.s3Client = s3Client
        this.archiveService = archiveService
        this.s3Bucket = s3Bucket
        this.s3Prefix = s3Prefix
    }

    @Scheduled(cron = "0 0 0 * * ?")
    fun compressAndUpload() {
        val yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1)
        compressAndUploadDate(yesterday)
    }

    fun compressAndUploadDate(date: LocalDate) {
        val dateDir = archiveService.getDateDir(date)
        if (!Files.exists(dateDir) || !Files.isDirectory(dateDir)) {
            Log.infof("No archive directory for %s, skipping", date)
            return
        }

        val srdFiles = Files.list(dateDir).use { stream ->
            stream.filter { it.toString().endsWith(".srd") }
                .sorted()
                .toList()
        }

        if (srdFiles.isEmpty()) {
            Log.infof("No SRD files for %s, skipping", date)
            return
        }

        val tarGzPath = archiveService.archiveDir.resolve("$date.tar.gz")
        try {
            createTarGz(tarGzPath, srdFiles, date.toString())
            uploadToS3(tarGzPath, date)
            srdFiles.forEach { Files.deleteIfExists(it) }
            Files.deleteIfExists(dateDir)
            Files.deleteIfExists(tarGzPath)
            Log.infof("Archived and uploaded %d files for %s to S3", srdFiles.size, date)
        } catch (e: Exception) {
            Log.errorf(e, "Failed to archive/upload files for %s", date)
            Files.deleteIfExists(tarGzPath)
        }
    }

    private fun createTarGz(outputPath: Path, files: List<Path>, dirName: String) {
        FileOutputStream(outputPath.toFile()).use { fos ->
            BufferedOutputStream(fos).use { bos ->
                GZIPOutputStream(bos).use { gzos ->
                    TarArchiveOutputStream(gzos).use { tar ->
                        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                        for (file in files) {
                            val entryName = "$dirName/${file.fileName}"
                            val entry = TarArchiveEntry(file.toFile(), entryName)
                            tar.putArchiveEntry(entry)
                            Files.copy(file, tar)
                            tar.closeArchiveEntry()
                        }
                    }
                }
            }
        }
    }

    private fun uploadToS3(tarGzPath: Path, date: LocalDate) {
        val key = "${s3Prefix}${date}/${date}.tar.gz"
        val request = PutObjectRequest.builder()
            .bucket(s3Bucket)
            .key(key)
            .build()
        s3Client.putObject(request, RequestBody.fromFile(tarGzPath))
    }
}
