package si.rainalator.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.time.ZonedDateTime

class RawArchiveServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var archiveService: RawArchiveService

    @BeforeEach
    fun setUp() {
        archiveService = RawArchiveService(tempDir)
    }

    @Test
    fun `creates date subdirectory and file`() {
        val scanTime = ZonedDateTime.of(2026, 4, 5, 12, 30, 0, 0, ZoneOffset.UTC)
        archiveService.saveRawSrd(scanTime, "test data")

        val dateDir = tempDir.resolve("2026-04-05")
        assertThat(Files.exists(dateDir)).isTrue()

        val files = Files.list(dateDir).use { it.toList() }
        assertThat(files).hasSize(1)
        assertThat(files[0].fileName.toString()).endsWith(".srd")
        assertThat(Files.readString(files[0])).isEqualTo("test data")
    }

    @Test
    fun `multiple saves on same day go to same directory`() {
        val t1 = ZonedDateTime.of(2026, 4, 5, 12, 0, 0, 0, ZoneOffset.UTC)
        val t2 = ZonedDateTime.of(2026, 4, 5, 12, 5, 0, 0, ZoneOffset.UTC)

        archiveService.saveRawSrd(t1, "data1")
        archiveService.saveRawSrd(t2, "data2")

        val dateDir = tempDir.resolve("2026-04-05")
        val files = Files.list(dateDir).use { it.toList() }
        assertThat(files).hasSize(2)
    }

    @Test
    fun `different days go to different directories`() {
        val t1 = ZonedDateTime.of(2026, 4, 5, 12, 0, 0, 0, ZoneOffset.UTC)
        val t2 = ZonedDateTime.of(2026, 4, 6, 12, 0, 0, 0, ZoneOffset.UTC)

        archiveService.saveRawSrd(t1, "data1")
        archiveService.saveRawSrd(t2, "data2")

        assertThat(Files.exists(tempDir.resolve("2026-04-05"))).isTrue()
        assertThat(Files.exists(tempDir.resolve("2026-04-06"))).isTrue()

        val files1 = Files.list(tempDir.resolve("2026-04-05")).use { it.toList() }
        val files2 = Files.list(tempDir.resolve("2026-04-06")).use { it.toList() }
        assertThat(files1).hasSize(1)
        assertThat(files2).hasSize(1)
    }
}
