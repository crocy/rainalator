package si.rainalator.analysis

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RainfallColorScaleTest {

    private val colorScale = RainfallColorScale()

    /** A one-hour frame makes the rate scale's paint thresholds identical to its mm/h legend. */
    private val scale = colorScale.overlayScale(ColorScaleMode.RATE, spanHours = 1.0)

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

    @Nested
    inner class RateScaleOverBuckets {

        @Test
        fun `bands on the mean rate over the bucket, not the raw depth`() {
            val sixHour = colorScale.overlayScale(ColorScaleMode.RATE, spanHours = 6.0)

            // 3 mm collected over 6 h is 0.5 mm/h — the same band as 0.5 mm/h in a one-hour frame
            assertThat(sixHour.toArgb(3.0)).isEqualTo(scale.toArgb(0.5))
        }

        @Test
        fun `legend stays in mm per hour whatever the bucket span`() {
            val sixHour = colorScale.overlayScale(ColorScaleMode.RATE, spanHours = 6.0)

            assertThat(sixHour.unit).isEqualTo("mm/h")
            assertThat(sixHour.legendThresholds).isEqualTo(scale.legendThresholds)
        }

        @Test
        fun `legend has fourteen ascending thresholds`() {
            assertThat(scale.legendThresholds).hasSize(14)
            assertThat(scale.legendThresholds).isSorted()
            assertThat(scale.legendThresholds.first()).isCloseTo(0.40, Offset.offset(1e-9))
            assertThat(scale.legendThresholds.last()).isCloseTo(158.49, Offset.offset(1e-9))
        }
    }

    @Nested
    inner class FixedDepthScale {

        private val fixed = colorScale.overlayScale(ColorScaleMode.FIXED, spanHours = 6.0)

        @Test
        fun `thresholds are the agreed absolute mm depths`() {
            assertThat(fixed.legendThresholds).containsExactly(
                0.2, 0.5, 1.0, 2.0, 4.0, 6.0, 10.0, 15.0, 25.0, 40.0, 60.0, 90.0, 130.0, 200.0,
            )
        }

        @Test
        fun `legend unit is mm`() {
            assertThat(fixed.unit).isEqualTo("mm")
        }

        @Test
        fun `thresholds do not depend on the bucket span`() {
            val oneHour = colorScale.overlayScale(ColorScaleMode.FIXED, spanHours = 1.0)

            assertThat(oneHour.legendThresholds).isEqualTo(fixed.legendThresholds)
            assertThat(oneHour.toArgb(7.0)).isEqualTo(fixed.toArgb(7.0))
        }

        @Test
        fun `a trace below the first threshold takes the first band`() {
            assertThat(fixed.toArgb(0.1)).isEqualTo(RainfallColorScale.BAND_COLORS.first())
        }

        @Test
        fun `a depth on a threshold moves up a band`() {
            assertThat(fixed.toArgb(1.0)).isNotEqualTo(fixed.toArgb(0.99))
        }

        @Test
        fun `above the top threshold takes the last band`() {
            assertThat(fixed.toArgb(500.0)).isEqualTo(RainfallColorScale.BAND_COLORS.last())
        }

        @Test
        fun `zero and NaN stay transparent`() {
            assertThat(alpha(fixed.toArgb(0.0))).isEqualTo(0)
            assertThat(alpha(fixed.toArgb(Double.NaN))).isEqualTo(0)
        }
    }

    @Nested
    inner class AutofitScale {

        private val autofit = colorScale.overlayScale(ColorScaleMode.AUTOFIT, spanHours = 6.0, scaleMax = 100.0)
        private val fixed = colorScale.overlayScale(ColorScaleMode.FIXED, spanHours = 6.0)

        @Test
        fun `top threshold equals scaleMax`() {
            assertThat(autofit.legendThresholds.last()).isCloseTo(100.0, Offset.offset(1e-9))
        }

        @Test
        fun `thresholds are the fixed thresholds rescaled, preserving their spacing`() {
            assertThat(autofit.legendThresholds).hasSize(14)
            autofit.legendThresholds.zip(fixed.legendThresholds) { fitted, absolute ->
                assertThat(fitted).isCloseTo(absolute / 2.0, Offset.offset(1e-9))
            }
        }

        @Test
        fun `legend unit is mm`() {
            assertThat(autofit.unit).isEqualTo("mm")
        }

        @Test
        fun `bands match the fixed scale once rescaled`() {
            assertThat(autofit.toArgb(2.5)).isEqualTo(fixed.toArgb(5.0))
            assertThat(autofit.toArgb(50.0)).isEqualTo(fixed.toArgb(100.0))
        }
    }

    @Nested
    inner class LegendColors {

        @Test
        fun `every mode reuses the same fifteen bands in order`() {
            val fixed = colorScale.overlayScale(ColorScaleMode.FIXED, spanHours = 1.0)
            val autofit = colorScale.overlayScale(ColorScaleMode.AUTOFIT, spanHours = 1.0, scaleMax = 50.0)

            assertThat(scale.colors).hasSize(15)
            assertThat(fixed.colors).isEqualTo(scale.colors)
            assertThat(autofit.colors).isEqualTo(scale.colors)
        }

        @Test
        fun `hex legend drops alpha and keeps band order`() {
            val hex = scale.legendColorsHex()

            assertThat(hex).hasSize(15)
            assertThat(hex.first()).isEqualTo("085AFE")
            assertThat(hex.last()).isEqualTo("CB00CC")
            assertThat(hex).allMatch { it.matches(Regex("[0-9A-F]{6}")) }
        }
    }
}
