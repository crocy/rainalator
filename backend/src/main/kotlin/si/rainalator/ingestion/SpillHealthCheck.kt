package si.rainalator.ingestion

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.health.HealthCheck
import org.eclipse.microprofile.health.HealthCheckResponse
import org.eclipse.microprofile.health.Readiness

@Readiness
@ApplicationScoped
class SpillHealthCheck(private val spillService: SpillService) : HealthCheck {

    override fun call(): HealthCheckResponse {
        val pending = spillService.pendingCount()
        val builder = HealthCheckResponse.named("spill-directory").up()
        if (pending > 0) {
            builder.withData("pending-files", pending.toLong())
        }
        return builder.build()
    }
}
