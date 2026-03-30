package si.rainalator.analysis

/**
 * Maps rainfall intensity (mm/h) to ARGB colors for radar overlay rendering.
 * Uses a standard meteorological color scale with increasing opacity for heavier rain.
 */
class RainfallColorScale {

    fun toArgb(mmPerHour: Double): Int {
        if (mmPerHour.isNaN() || mmPerHour <= 0.0) return 0x00000000

        return when {
            mmPerHour < 0.5  -> 0x8000BFFF.toInt()   // light blue, semi-transparent
            mmPerHour < 2.0  -> 0xA00000FF.toInt()    // blue
            mmPerHour < 5.0  -> 0xC000CC00.toInt()    // green
            mmPerHour < 10.0 -> 0xD0FFFF00.toInt()    // yellow
            mmPerHour < 20.0 -> 0xE0FF8000.toInt()    // orange
            mmPerHour < 50.0 -> 0xF0FF0000.toInt()    // red
            else             -> 0xFF800000.toInt()     // dark red, fully opaque
        }
    }
}
