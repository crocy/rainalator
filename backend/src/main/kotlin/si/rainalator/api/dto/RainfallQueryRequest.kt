package si.rainalator.api.dto

data class RainfallQueryRequest(
    val polygon: String = "",
    val from: String = "",
    val to: String = "",
    /** Null means per-scan: every scan becomes a bucket of one. */
    val bucketMinutes: Int? = null,
)
