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

/** One timeline frame's statistics, folded from the scans that fall inside it. */
data class RainfallBucket(
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    /** Area-averaged depth collected during this bucket. */
    val depthMm: Double,
    /** Depth spread over the bucket's actual span, so a clipped bucket is not diluted by its nominal width. */
    val meanRateMmH: Double,
    val minRateMmH: Double,
    val maxRateMmH: Double,
    val volumeM3: Double,
    val scanCount: Int,
    val partial: Boolean,
)

data class RainfallResult(
    /** Per-scan clip statistics — the rows the buckets are folded from. */
    val scans: List<ScanAnalysis>,
    val buckets: List<RainfallBucket>,
    /** Area-averaged depth — what a gauge inside the polygon would collect on average. Independent of polygon size. */
    val accumulatedRainfallMm: Double,
    /** Total water collected over the polygon: depth × area. Grows with polygon size. */
    val totalVolumeM3: Double,
    val areaKm2: Double,
    val bucketMinutes: Int,
)

/** A step of the timeline: one scan when un-bucketed, one bucket otherwise. */
data class Frame(
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val partial: Boolean,
)

data class FrameList(
    val frames: List<Frame>,
    val bucketMinutes: Int,
    /** Set only for autofit: the deepest frame in the period, so every frame shares one comparable scale. */
    val scaleMaxMm: Double?,
)

data class OverlayImage(
    val pngBytes: ByteArray,
    val bounds: GeoBounds,
    val legendUnit: String,
    val legendThresholds: List<Double>,
    val legendColorsHex: List<String>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OverlayImage) return false
        return pngBytes.contentEquals(other.pngBytes) &&
            bounds == other.bounds &&
            legendUnit == other.legendUnit &&
            legendThresholds == other.legendThresholds &&
            legendColorsHex == other.legendColorsHex
    }

    override fun hashCode(): Int {
        var result = pngBytes.contentHashCode()
        result = 31 * result + bounds.hashCode()
        result = 31 * result + legendUnit.hashCode()
        result = 31 * result + legendThresholds.hashCode()
        result = 31 * result + legendColorsHex.hashCode()
        return result
    }
}

data class GeoBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
)
