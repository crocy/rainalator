package si.rainalator.ingestion

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3ArchivalSchedulerTest {

    companion object {
        @Container
        @JvmStatic
        val localStack: LocalStackContainer = LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.4")
        ).withServices(LocalStackContainer.Service.S3)
    }

    private lateinit var s3Client: S3Client
    private val bucket = "rainalator-raw-archive"
    private val prefix = "srd3/"

    @BeforeAll
    fun setup() {
        s3Client = S3Client.builder()
            .endpointOverride(localStack.getEndpointOverride(LocalStackContainer.Service.S3))
            .region(Region.of(localStack.region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(localStack.accessKey, localStack.secretKey)
                )
            )
            .build()
        s3Client.createBucket { it.bucket(bucket) }
    }

    @BeforeEach
    fun cleanBucket() {
        val objects = s3Client.listObjects { it.bucket(bucket) }
        for (obj in objects.contents()) {
            s3Client.deleteObject { it.bucket(bucket).key(obj.key()) }
        }
    }

    @AfterAll
    fun teardown() {
        s3Client.close()
    }

    @Test
    fun `compresses and uploads yesterday's files to S3`(@TempDir tempDir: Path) {
        val archiveService = RawArchiveService(tempDir)
        val yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1)

        archiveService.saveRawSrd(yesterday.atTime(12, 0).atZone(ZoneOffset.UTC), "data1")
        archiveService.saveRawSrd(yesterday.atTime(12, 5).atZone(ZoneOffset.UTC), "data2")

        val scheduler = S3ArchivalScheduler(s3Client, archiveService, bucket, prefix)
        scheduler.compressAndUploadDate(yesterday)

        val objects = s3Client.listObjects { it.bucket(bucket).prefix(prefix) }
        assertThat(objects.contents()).anyMatch { it.key().contains(yesterday.toString()) }
    }

    @Test
    fun `deletes local files after successful upload`(@TempDir tempDir: Path) {
        val archiveService = RawArchiveService(tempDir)
        val yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1)

        archiveService.saveRawSrd(yesterday.atTime(10, 0).atZone(ZoneOffset.UTC), "data")

        val scheduler = S3ArchivalScheduler(s3Client, archiveService, bucket, prefix)
        scheduler.compressAndUploadDate(yesterday)

        assertThat(Files.exists(archiveService.getDateDir(yesterday))).isFalse()
        assertThat(Files.exists(tempDir.resolve("$yesterday.tar.gz"))).isFalse()
    }

    @Test
    fun `retains local files when S3 upload fails`(@TempDir tempDir: Path) {
        val archiveService = RawArchiveService(tempDir)
        val yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1)

        archiveService.saveRawSrd(yesterday.atTime(12, 0).atZone(ZoneOffset.UTC), "data")

        val scheduler = S3ArchivalScheduler(s3Client, archiveService, "nonexistent-bucket", prefix)
        scheduler.compressAndUploadDate(yesterday)

        val dateDir = archiveService.getDateDir(yesterday)
        assertThat(Files.exists(dateDir)).isTrue()
        val files = Files.list(dateDir).use { it.toList() }
        assertThat(files).hasSize(1)
        assertThat(Files.exists(tempDir.resolve("$yesterday.tar.gz"))).isFalse()
    }

    @Test
    fun `handles empty directory gracefully`(@TempDir tempDir: Path) {
        val archiveService = RawArchiveService(tempDir)
        val yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1)
        Files.createDirectories(archiveService.getDateDir(yesterday))

        val scheduler = S3ArchivalScheduler(s3Client, archiveService, bucket, prefix)
        scheduler.compressAndUploadDate(yesterday)
    }

    @Test
    fun `handles missing directory gracefully`(@TempDir tempDir: Path) {
        val archiveService = RawArchiveService(tempDir)
        val yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1)

        val scheduler = S3ArchivalScheduler(s3Client, archiveService, bucket, prefix)
        scheduler.compressAndUploadDate(yesterday)
    }

    @Test
    fun `produces valid tar gz with correct contents`(@TempDir tempDir: Path) {
        val archiveService = RawArchiveService(tempDir)
        val yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1)

        archiveService.saveRawSrd(yesterday.atTime(12, 0).atZone(ZoneOffset.UTC), "content-A")
        archiveService.saveRawSrd(yesterday.atTime(12, 5).atZone(ZoneOffset.UTC), "content-B")

        val scheduler = S3ArchivalScheduler(s3Client, archiveService, bucket, prefix)
        scheduler.compressAndUploadDate(yesterday)

        val key = "${prefix}${yesterday}/${yesterday}.tar.gz"
        val downloadPath = tempDir.resolve("downloaded.tar.gz")
        s3Client.getObject({ it.bucket(bucket).key(key) }, downloadPath)

        val contents = mutableListOf<String>()
        FileInputStream(downloadPath.toFile()).use { fis ->
            GZIPInputStream(fis).use { gis ->
                TarArchiveInputStream(gis).use { tis ->
                    var entry = tis.nextEntry
                    while (entry != null) {
                        contents.add(String(tis.readNBytes(entry.size.toInt())))
                        entry = tis.nextEntry
                    }
                }
            }
        }

        assertThat(contents).hasSize(2)
        assertThat(contents).containsExactlyInAnyOrder("content-A", "content-B")
    }
}
