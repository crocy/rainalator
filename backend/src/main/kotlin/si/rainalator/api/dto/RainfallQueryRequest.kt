package si.rainalator.api.dto

data class RainfallQueryRequest(
    val polygon: String = "",
    val from: String = "",
    val to: String = "",
)
