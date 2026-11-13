package si.rainalator.analysis

import java.util.Locale

enum class ColorScaleMode {
    /** Rain rate in mm/h — the per-scan view, and the only scale that reads as an intensity. */
    RATE,

    /** Absolute mm depth, so two periods of different length stay comparable. */
    FIXED,

    /** The absolute depth bands rescaled to the deepest bucket in the selected period. */
    AUTOFIT;

    companion object {
        fun parse(value: String): ColorScaleMode? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * The thresholds a rendered frame is painted with, plus the legend describing it.
 *
 * The two differ for [ColorScaleMode.RATE]: the raster holds the depth accumulated over the frame,
 * so a mm/h band edge has to be stretched over the frame's span before pixels are compared to it,
 * while the legend keeps advertising the mm/h edges the reader recognises.
 */
class OverlayScale(
    val unit: String,
    val legendThresholds: List<Double>,
    private val paintThresholds: List<Double>,
    val colors: List<Int>,
) {
    fun toArgb(depthMm: Double): Int {
        if (depthMm.isNaN() || depthMm <= 0.0) return TRANSPARENT
        val band = paintThresholds.indexOfFirst { depthMm < it }
        return if (band < 0) colors.last() else colors[band]
    }

    fun legendColorsHex(): List<String> = colors.map { String.format(Locale.ROOT, "%06X", it and 0xFFFFFF) }

    private companion object {
        const val TRANSPARENT = 0x00000000
    }
}

/**
 * Maps rainfall to ARGB colors for radar overlay rendering.
 * Colors extracted from the ARSO radar legend at si0-rrg.gif; every scale mode reuses the same
 * bands in the same order, so a colour means the same relative intensity whichever mode is on.
 */
class RainfallColorScale {

    fun overlayScale(mode: ColorScaleMode, spanHours: Double, scaleMax: Double? = null): OverlayScale =
        when (mode) {
            ColorScaleMode.RATE -> OverlayScale(
                unit = "mm/h",
                legendThresholds = RATE_THRESHOLDS,
                paintThresholds = RATE_THRESHOLDS.map { it * spanHours },
                colors = BAND_COLORS,
            )

            ColorScaleMode.FIXED -> depthScale(DEPTH_THRESHOLDS)

            ColorScaleMode.AUTOFIT -> {
                // The caller validates this; autofit without a maximum has nothing to fit to.
                val max = requireNotNull(scaleMax) { "autofit needs a scaleMax" }
                depthScale(DEPTH_THRESHOLDS.map { it / DEPTH_THRESHOLDS.last() * max })
            }
        }

    private fun depthScale(thresholds: List<Double>) = OverlayScale(
        unit = "mm",
        legendThresholds = thresholds,
        paintThresholds = thresholds,
        colors = BAND_COLORS,
    )

    companion object {
        /** The 15 ARSO legend bands, ascending. The top three carry more alpha so they read through the basemap. */
        val BAND_COLORS: List<Int> = listOf(
            0xE0085AFE.toInt(), // A: dark blue        RGB(8,90,254)
            0xE0008CFE.toInt(), // B: blue             RGB(0,140,254)
            0xE000AEFD.toInt(), // C: medium blue      RGB(0,174,253)
            0xE000C8FE.toInt(), // D: sky blue         RGB(0,200,254)
            0xE004D883.toInt(), // E: teal             RGB(4,216,131)
            0xE042EB42.toInt(), // F: green            RGB(66,235,66)
            0xE06CF900.toInt(), // G: yellow-green     RGB(108,249,0)
            0xE0B8FA00.toInt(), // H: lime             RGB(184,250,0)
            0xE0F9FA00.toInt(), // I: yellow           RGB(249,250,0)
            0xE0FEC600.toInt(), // J: gold             RGB(254,198,0)
            0xE0FE8400.toInt(), // K: orange           RGB(254,132,0)
            0xE0FF3E01.toInt(), // L: red-orange       RGB(255,62,1)
            0xF0D30000.toInt(), // M: red              RGB(211,0,0)
            0xF0B50303.toInt(), // N: dark red         RGB(181,3,3)
            0xF0CB00CC.toInt(), // O+: magenta         RGB(203,0,204)
        )

        /** mm/h band edges, one per 2 dB step of the SRD encoding. */
        val RATE_THRESHOLDS: List<Double> = listOf(
            0.40, 0.63, 1.0, 1.58, 2.51, 3.98, 6.31, 10.0, 15.85, 25.12, 39.81, 63.10, 100.0, 158.49,
        )

        /** Absolute mm depth band edges: 0.2 mm is a measurable trace, 200 mm an extreme multi-day total. */
        val DEPTH_THRESHOLDS: List<Double> = listOf(
            0.2, 0.5, 1.0, 2.0, 4.0, 6.0, 10.0, 15.0, 25.0, 40.0, 60.0, 90.0, 130.0, 200.0,
        )
    }
}
