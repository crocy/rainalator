package si.rainalator.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.health.HealthCheckResponse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.ZoneOffset
import java.time.ZonedDateTime

class SpillHealthCheckTest {

    @Test
    fun `reports UP with no pending files`(@TempDir tempDir: Path) {
        val spillService = SpillService(tempDir)
        val healthCheck = SpillHealthCheck(spillService)

        val response = healthCheck.call()

        assertThat(response.status).isEqualTo(HealthCheckResponse.Status.UP)
        assertThat(response.data).isEmpty()
    }

    @Test
    fun `reports UP with pending-files count when files exist`(@TempDir tempDir: Path) {
        val spillService = SpillService(tempDir)
        spillService.saveToSpillDir(
            ZonedDateTime.of(2026, 4, 5, 12, 0, 0, 0, ZoneOffset.UTC),
            "test data"
        )
        val healthCheck = SpillHealthCheck(spillService)

        val response = healthCheck.call()

        assertThat(response.status).isEqualTo(HealthCheckResponse.Status.UP)
        assertThat(response.data.get()).containsEntry("pending-files", 1L)
    }
}
