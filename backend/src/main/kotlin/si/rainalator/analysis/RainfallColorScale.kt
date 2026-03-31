package si.rainalator.analysis

/**
 * Maps rainfall intensity (mm/h) to ARGB colors for radar overlay rendering.
 * Colors extracted from the ARSO radar legend at si0-rrg.gif.
 * Each 2dB step in the SRD encoding gets its own color band.
 */
class RainfallColorScale {

    fun toArgb(mmPerHour: Double): Int {
        if (mmPerHour.isNaN() || mmPerHour <= 0.0) return 0x00000000

        // ARSO exact color scale: 16 bands matching SRD character codes A-P
        // Thresholds at each 2dB step: 0.25, 0.40, 0.63, 1.0, 1.58, 2.51, ...
        return when {
            mmPerHour < 0.40  -> 0xE0085AFE.toInt()   // A: dark blue        RGB(8,90,254)
            mmPerHour < 0.63  -> 0xE0008CFE.toInt()   // B: blue             RGB(0,140,254)
            mmPerHour < 1.0   -> 0xE000AEFD.toInt()   // C: medium blue      RGB(0,174,253)
            mmPerHour < 1.58  -> 0xE000C8FE.toInt()   // D: sky blue         RGB(0,200,254)
            mmPerHour < 2.51  -> 0xE004D883.toInt()   // E: teal             RGB(4,216,131)
            mmPerHour < 3.98  -> 0xE042EB42.toInt()   // F: green            RGB(66,235,66)
            mmPerHour < 6.31  -> 0xE06CF900.toInt()   // G: yellow-green     RGB(108,249,0)
            mmPerHour < 10.0  -> 0xE0B8FA00.toInt()   // H: lime             RGB(184,250,0)
            mmPerHour < 15.85 -> 0xE0F9FA00.toInt()   // I: yellow           RGB(249,250,0)
            mmPerHour < 25.12 -> 0xE0FEC600.toInt()   // J: gold             RGB(254,198,0)
            mmPerHour < 39.81 -> 0xE0FE8400.toInt()   // K: orange           RGB(254,132,0)
            mmPerHour < 63.10 -> 0xE0FF3E01.toInt()   // L: red-orange       RGB(255,62,1)
            mmPerHour < 100.0 -> 0xF0D30000.toInt()   // M: red              RGB(211,0,0)
            mmPerHour < 158.49-> 0xF0B50303.toInt()   // N: dark red         RGB(181,3,3)
            else              -> 0xF0CB00CC.toInt()    // O+: magenta         RGB(203,0,204)
        }
    }
}
