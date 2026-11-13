package si.rainalator.analysis

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class BucketWindowTest {

    private val to = ZonedDateTime.of(2026, 3, 30, 12, 0, 0, 0, ZoneOffset.UTC)

    private fun minutesBefore(minutes: Long) = to.minusMinutes(minutes)

    @Nested
    inner class EvenlyDividedWindow {

        @Test
        fun `walks backwards from the window end`() {
            val windows = bucketWindows(minutesBefore(360), to, bucketMinutes = 120)

            assertThat(windows).hasSize(3)
            assertThat(windows.map { it.start }).containsExactly(
                minutesBefore(360), minutesBefore(240), minutesBefore(120),
            )
            assertThat(windows.map { it.end }).containsExactly(
                minutesBefore(240), minutesBefore(120), to,
            )
        }

        @Test
        fun `returns windows oldest first so the timeline plays forward`() {
            val windows = bucketWindows(minutesBefore(360), to, bucketMinutes = 120)

            assertThat(windows.map { it.start }).isSorted()
        }

        @Test
        fun `marks no window partial`() {
            val windows = bucketWindows(minutesBefore(360), to, bucketMinutes = 120)

            assertThat(windows.map { it.partial }).containsOnly(false)
        }

        @Test
        fun `windows are contiguous and cover the whole period`() {
            val windows = bucketWindows(minutesBefore(360), to, bucketMinutes = 120)

            assertThat(windows.first().start).isEqualTo(minutesBefore(360))
            assertThat(windows.last().end).isEqualTo(to)
            windows.zipWithNext { earlier, later -> assertThat(earlier.end).isEqualTo(later.start) }
        }
    }

    @Nested
    inner class UnevenWindow {

        @Test
        fun `clamps the oldest window to the window start and marks it partial`() {
            val windows = bucketWindows(minutesBefore(330), to, bucketMinutes = 120)

            assertThat(windows).hasSize(3)
            assertThat(windows.first().start).isEqualTo(minutesBefore(330))
            assertThat(windows.first().end).isEqualTo(minutesBefore(240))
            assertThat(windows.first().partial).isTrue()
            assertThat(windows.drop(1).map { it.partial }).containsOnly(false)
        }

        @Test
        fun `the newest window always keeps its full width`() {
            val windows = bucketWindows(minutesBefore(330), to, bucketMinutes = 120)

            assertThat(windows.last().start).isEqualTo(minutesBefore(120))
            assertThat(windows.last().end).isEqualTo(to)
        }

        @Test
        fun `a window shorter than one bucket yields a single partial window`() {
            val windows = bucketWindows(minutesBefore(45), to, bucketMinutes = 120)

            assertThat(windows).hasSize(1)
            assertThat(windows.single().start).isEqualTo(minutesBefore(45))
            assertThat(windows.single().end).isEqualTo(to)
            assertThat(windows.single().partial).isTrue()
        }

        @Test
        fun `a bucket exactly the width of the period is not partial`() {
            val windows = bucketWindows(minutesBefore(120), to, bucketMinutes = 120)

            assertThat(windows).hasSize(1)
            assertThat(windows.single().partial).isFalse()
        }
    }

    @Nested
    inner class Normalisation {

        @Test
        fun `edges are reported in UTC whatever offset the caller used`() {
            val zoned = to.withZoneSameInstant(ZoneOffset.ofHours(2))

            val windows = bucketWindows(zoned.minusMinutes(120), zoned, bucketMinutes = 60)

            assertThat(windows.map { it.start.zone }).containsOnly(ZoneOffset.UTC)
            assertThat(windows.map { it.end.zone }).containsOnly(ZoneOffset.UTC)
            assertThat(windows.last().end).isEqualTo(to)
        }

        @Test
        fun `a zero-length period yields no windows`() {
            assertThat(bucketWindows(to, to, bucketMinutes = 60)).isEmpty()
        }
    }
}
