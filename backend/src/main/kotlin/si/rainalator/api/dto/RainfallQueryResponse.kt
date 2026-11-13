package si.rainalator.api.dto

data class BucketStats(
    val start: String,
    val end: String,
    val depthMm: Double,
    val meanRateMmH: Double,
    val minRateMmH: Double,
    val maxRateMmH: Double,
    val volumeM3: Double,
    val scanCount: Int,
    val partial: Boolean,
)

data class RainfallQueryResponse(
    val buckets: List<BucketStats>,
    /** Area-averaged depth in mm — independent of how large the selected polygon is. */
    val accumulatedRainfallMm: Double,
    /** Total water collected over the polygon in m³ — grows with the selected area. */
    val totalVolumeM3: Double,
    val areaKm2: Double,
    val bucketCount: Int,
    val bucketMinutes: Int,
)

data class FrameStats(
    val start: String,
    val end: String,
    val partial: Boolean,
)

data class FramesResponse(
    val frames: List<FrameStats>,
    val count: Int,
    val bucketMinutes: Int,
    /** Only sent for the autofit scale; omitted otherwise. */
    val scaleMaxMm: Double? = null,
)
