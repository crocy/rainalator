package si.rainalator.api.dto

data class ScanStats(
    val scanTime: String,
    val mean: Double,
    val min: Double,
    val max: Double,
    val sum: Double,
    val count: Long,
)

data class RainfallQueryResponse(
    val scans: List<ScanStats>,
    val accumulatedRainfallMm: Double,
    val scanCount: Int,
    val intervalMinutes: Int = 5,
)

data class ScanTimesResponse(
    val scanTimes: List<String>,
    val count: Int,
)
