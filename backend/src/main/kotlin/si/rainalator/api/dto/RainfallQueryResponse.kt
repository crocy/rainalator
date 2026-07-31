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
    /** Area-averaged depth in mm — independent of how large the selected polygon is. */
    val accumulatedRainfallMm: Double,
    /** Total water collected over the polygon in m³ — grows with the selected area. */
    val totalVolumeM3: Double,
    val areaKm2: Double,
    val scanCount: Int,
    val intervalMinutes: Int = 5,
)

data class ScanTimesResponse(
    val scanTimes: List<String>,
    val count: Int,
)
