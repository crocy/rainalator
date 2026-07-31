package si.rainalator.analysis

import java.time.ZonedDateTime

data class ScanAnalysis(
    val scanTime: ZonedDateTime,
    val count: Long,
    val sum: Double,
    val mean: Double,
    val stddev: Double,
    val min: Double,
    val max: Double,
)

data class RainfallResult(
    val scans: List<ScanAnalysis>,
    /** Area-averaged depth — what a gauge inside the polygon would collect on average. Independent of polygon size. */
    val accumulatedRainfallMm: Double,
    /** Total water collected over the polygon: depth × area. Grows with polygon size. */
    val totalVolumeM3: Double,
    val areaKm2: Double,
)

data class OverlayImage(
    val pngBytes: ByteArray,
    val bounds: GeoBounds,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OverlayImage) return false
        return pngBytes.contentEquals(other.pngBytes) && bounds == other.bounds
    }

    override fun hashCode(): Int = 31 * pngBytes.contentHashCode() + bounds.hashCode()
}

data class GeoBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
)
