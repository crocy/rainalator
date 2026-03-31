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
        fun `drizzle 0_3 returns dark blue`() {
            val argb = scale.toArgb(0.3)
            assertThat(red(argb)).isEqualTo(8)
            assertThat(green(argb)).isEqualTo(90)
            assertThat(blue(argb)).isEqualTo(254)
        }

        @Test
        fun `light rain 0_5 returns blue`() {
            val argb = scale.toArgb(0.5)
            assertThat(red(argb)).isEqualTo(0)
            assertThat(green(argb)).isEqualTo(140)
            assertThat(blue(argb)).isEqualTo(254)
        }

        @Test
        fun `rain 2_0 returns teal`() {
            val argb = scale.toArgb(2.0)
            assertThat(red(argb)).isEqualTo(4)
            assertThat(green(argb)).isEqualTo(216)
            assertThat(blue(argb)).isEqualTo(131)
        }

        @Test
        fun `moderate rain 8_0 returns lime`() {
            val argb = scale.toArgb(8.0)
            assertThat(red(argb)).isEqualTo(184)
            assertThat(green(argb)).isEqualTo(250)
            assertThat(blue(argb)).isEqualTo(0)
        }

        @Test
        fun `heavy rain 30_0 returns orange`() {
            val argb = scale.toArgb(30.0)
            assertThat(red(argb)).isEqualTo(254)
            assertThat(green(argb)).isEqualTo(132)
            assertThat(blue(argb)).isEqualTo(0)
        }

        @Test
        fun `extreme rain 80_0 returns red`() {
            val argb = scale.toArgb(80.0)
            assertThat(red(argb)).isEqualTo(211)
            assertThat(green(argb)).isEqualTo(0)
            assertThat(blue(argb)).isEqualTo(0)
        }

        @Test
        fun `torrential rain 200_0 returns magenta`() {
            val argb = scale.toArgb(200.0)
            assertThat(red(argb)).isEqualTo(203)
            assertThat(green(argb)).isEqualTo(0)
            assertThat(blue(argb)).isEqualTo(204)
        }
    }

    @Nested
    inner class AlphaProgression {

        @Test
        fun `alpha increases with intensity`() {
            val alphaDrizzle = alpha(scale.toArgb(0.3))
            val alphaModerate = alpha(scale.toArgb(3.0))
            val alphaHeavy = alpha(scale.toArgb(30.0))
            val alphaExtreme = alpha(scale.toArgb(120.0))

            assertThat(alphaDrizzle).isLessThanOrEqualTo(alphaModerate)
            assertThat(alphaModerate).isLessThanOrEqualTo(alphaHeavy)
            assertThat(alphaHeavy).isLessThanOrEqualTo(alphaExtreme)
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `boundary 0_40 assigns to next bucket`() {
            val argbBelow = scale.toArgb(0.39)
            val argbAt = scale.toArgb(0.40)
            // 0.40 should be in the next bucket (blue), different from 0.39 (dark blue)
            assertThat(argbAt).isNotEqualTo(argbBelow)
        }

        @Test
        fun `just above zero is not transparent`() {
            val argb = scale.toArgb(0.01)
            assertThat(alpha(argb)).isGreaterThan(0)
        }
    }
}
