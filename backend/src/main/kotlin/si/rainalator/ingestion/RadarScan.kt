package si.rainalator.ingestion

/**
 * Parsed radar scan with header metadata and rainfall values.
 * [values] is a row-major float array of size [header].width * [header].height.
 * Each value is rainfall rate in mm/h, or [Float.NaN] for nodata.
 */
data class RadarScan(
    val header: SrdHeader,
    val values: FloatArray,
) {
    fun valueAt(col: Int, row: Int): Float = values[row * header.width + col]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RadarScan) return false
        return header == other.header && values.contentEquals(other.values)
    }

    override fun hashCode(): Int = 31 * header.hashCode() + values.contentHashCode()
}
