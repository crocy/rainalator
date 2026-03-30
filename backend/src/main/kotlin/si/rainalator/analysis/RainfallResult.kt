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
    val accumulatedRainfallMm: Double,
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
