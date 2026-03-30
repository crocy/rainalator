package si.rainalator.analysis

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RainfallColorScaleTest {

    private val scale = RainfallColorScale()

    private fun alpha(argb: Int): Int = (argb shr 24) and 0xFF
    private fun red(argb: Int): Int = (argb shr 16) and 0xFF
    private fun green(argb: Int): Int = (argb shr 8) and 0xFF
    private fun blue(argb: Int): Int = argb and 0xFF

    @Nested
    inner class TransparentCases {

        @Test
        fun `zero rainfall returns transparent`() {
            assertThat(alpha(scale.toArgb(0.0))).isEqualTo(0)
        }

        @Test
        fun `NaN returns transparent`() {
            assertThat(alpha(scale.toArgb(Double.NaN))).isEqualTo(0)
        }

        @Test
        fun `negative value returns transparent`() {
            assertThat(alpha(scale.toArgb(-1.0))).isEqualTo(0)
        }
    }

    @Nested
    inner class ColorBuckets {

        @Test
        fun `light rain 0_3 returns light blue`() {
            val argb = scale.toArgb(0.3)
            assertThat(alpha(argb)).isGreaterThan(0)
            assertThat(blue(argb)).isGreaterThan(red(argb))
        }

        @Test
        fun `moderate rain 3_0 returns green`() {
            val argb = scale.toArgb(3.0)
            assertThat(alpha(argb)).isGreaterThan(0)
            assertThat(green(argb)).isGreaterThan(red(argb))
            assertThat(green(argb)).isGreaterThan(blue(argb))
        }

        @Test
        fun `heavy rain 15_0 returns orange`() {
            val argb = scale.toArgb(15.0)
            assertThat(alpha(argb)).isGreaterThan(0)
            assertThat(red(argb)).isGreaterThan(blue(argb))
        }

        @Test
        fun `extreme rain 60_0 returns dark red`() {
            val argb = scale.toArgb(60.0)
            assertThat(alpha(argb)).isEqualTo(0xFF)
            assertThat(red(argb)).isGreaterThan(0)
            assertThat(green(argb)).isEqualTo(0)
            assertThat(blue(argb)).isEqualTo(0)
        }
    }

    @Nested
    inner class AlphaProgression {

        @Test
        fun `alpha increases with intensity`() {
            val alphaLight = alpha(scale.toArgb(0.3))
            val alphaMod = alpha(scale.toArgb(3.0))
            val alphaHeavy = alpha(scale.toArgb(15.0))
            val alphaExtreme = alpha(scale.toArgb(60.0))

            assertThat(alphaLight).isLessThan(alphaMod)
            assertThat(alphaMod).isLessThan(alphaHeavy)
            assertThat(alphaHeavy).isLessThanOrEqualTo(alphaExtreme)
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `boundary 0_5 assigns to moderate bucket not light`() {
            val argbBelow = scale.toArgb(0.49)
            val argbAt = scale.toArgb(0.5)
            // 0.5 should be in the next bucket (blue), different from 0.49 (light blue)
            assertThat(argbAt).isNotEqualTo(argbBelow)
        }

        @Test
        fun `just above zero is not transparent`() {
            val argb = scale.toArgb(0.01)
            assertThat(alpha(argb)).isGreaterThan(0)
        }
    }
}
