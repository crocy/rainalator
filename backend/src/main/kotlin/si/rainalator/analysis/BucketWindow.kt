package si.rainalator.analysis

import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime

private const val NANOS_PER_HOUR = 3_600_000_000_000.0

/** The time span of one timeline frame. Half-open, except at the newest edge of the selected period. */
data class BucketWindow(
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    /** True when [bucketWindows] had to clip this window, i.e. it is narrower than the requested bucket. */
    val partial: Boolean,
)

/**
 * Splits the period into [bucketMinutes]-wide windows, oldest first.
 *
 * Edges anchor at [to] and walk backwards so the newest bucket always keeps its full width —
 * it is the one the user is watching — and only the oldest one can come out short.
 */
fun bucketWindows(from: ZonedDateTime, to: ZonedDateTime, bucketMinutes: Int): List<BucketWindow> {
    val windows = mutableListOf<BucketWindow>()
    var end = to
    while (end.isAfter(from)) {
        val fullWidthStart = end.minusMinutes(bucketMinutes.toLong())
        val partial = fullWidthStart.isBefore(from)
        val start = if (partial) from else fullWidthStart
        windows.add(BucketWindow(start.toUtc(), end.toUtc(), partial))
        end = start
    }
    return windows.reversed()
}

internal fun ZonedDateTime.toUtc(): ZonedDateTime = withZoneSameInstant(ZoneOffset.UTC)

internal fun hoursBetween(start: ZonedDateTime, end: ZonedDateTime): Double =
    Duration.between(start, end).toNanos() / NANOS_PER_HOUR
