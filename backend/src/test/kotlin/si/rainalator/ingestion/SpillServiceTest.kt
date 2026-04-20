package si.rainalator.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.time.ZonedDateTime

class SpillServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var spillService: SpillService

    @BeforeEach
    fun setUp() {
        spillService = SpillService(tempDir)
    }

    @Test
    fun `saves file with correct name format and content`() {
        val scanTime = ZonedDateTime.of(2026, 4, 5, 12, 30, 0, 0, ZoneOffset.UTC)
        val content = "test SRD data"

        spillService.saveToSpillDir(scanTime, content)

        val files = Files.list(tempDir).use { it.toList() }
        assertThat(files).hasSize(1)
        assertThat(files[0].fileName.toString()).isEqualTo("2026-04-05T12-30-00Z.srd")
        assertThat(Files.readString(files[0])).isEqualTo(content)
    }

    @Test
    fun `lists files sorted oldest-first`() {
        val t1 = ZonedDateTime.of(2026, 4, 5, 12, 0, 0, 0, ZoneOffset.UTC)
        val t2 = ZonedDateTime.of(2026, 4, 5, 12, 5, 0, 0, ZoneOffset.UTC)
        val t3 = ZonedDateTime.of(2026, 4, 5, 12, 10, 0, 0, ZoneOffset.UTC)

        spillService.saveToSpillDir(t3, "data3")
        spillService.saveToSpillDir(t1, "data1")
        spillService.saveToSpillDir(t2, "data2")

        val pending = spillService.listPendingFiles()
        assertThat(pending).hasSize(3)
        assertThat(Files.readString(pending[0])).isEqualTo("data1")
        assertThat(Files.readString(pending[1])).isEqualTo("data2")
        assertThat(Files.readString(pending[2])).isEqualTo("data3")
    }

    @Test
    fun `returns correct pending count`() {
        assertThat(spillService.pendingCount()).isEqualTo(0)

        spillService.saveToSpillDir(
            ZonedDateTime.of(2026, 4, 5, 12, 0, 0, 0, ZoneOffset.UTC),
            "data"
        )
        assertThat(spillService.pendingCount()).isEqualTo(1)
    }

    @Test
    fun `deletes spill file`() {
        spillService.saveToSpillDir(
            ZonedDateTime.of(2026, 4, 5, 12, 0, 0, 0, ZoneOffset.UTC),
            "data"
        )

        val files = spillService.listPendingFiles()
        spillService.deleteSpillFile(files[0])

        assertThat(spillService.pendingCount()).isEqualTo(0)
    }

    @Test
    fun `creates directory if missing`() {
        val nestedDir = tempDir.resolve("nested").resolve("spill")
        val service = SpillService(nestedDir)

        assertThat(Files.exists(nestedDir)).isTrue()
        assertThat(service.pendingCount()).isEqualTo(0)
    }
}
