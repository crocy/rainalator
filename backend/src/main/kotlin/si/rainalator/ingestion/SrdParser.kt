package si.rainalator.ingestion

import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.pow

/**
 * Parses SRD-3 format radar data files from ARSO.
 * Pure function — no side effects, no DB access.
 */
class SrdParser {

    fun parse(input: String): RadarScan {
        require(input.isNotBlank()) { "Input is empty" }

        val lines = input.lines()
        require(lines.first().trim() == "SRD-3") { "Not an SRD-3 file: '${lines.first().trim()}'" }

        val headerMap = mutableMapOf<String, String>()
        var dataStartLine = -1

        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed == "DATA") {
                dataStartLine = index + 1
                break
            }
            if (trimmed.startsWith("#") || trimmed == "COMMENT") continue
            val spaceIdx = trimmed.indexOf(' ')
            if (spaceIdx > 0) {
                val key = trimmed.substring(0, spaceIdx).trim().lowercase()
                val value = trimmed.substring(spaceIdx).trim()
                headerMap[key] = value
            }
        }

        require(dataStartLine > 0) { "No DATA marker found" }

        val header = buildHeader(headerMap)
        val values = parseDataGrid(lines, dataStartLine, header)

        return RadarScan(header, values)
    }

    private fun buildHeader(m: Map<String, String>): SrdHeader {
        val ncell = m["ncell"]?.split("\\s+".toRegex()) ?: error("Missing ncell")
        val cellsize = m["cellsize"]?.split("\\s+".toRegex()) ?: error("Missing cellsize")
        val ellipse = m["ellipse"]?.split("\\s+".toRegex()) ?: listOf("6371.0", "6371.0")
        val par = m["par"]?.split("\\s+".toRegex()) ?: listOf("0.0", "0.0")
        val origin = m["origin"]?.split("\\s+".toRegex()) ?: error("Missing origin")
        val shift = m["shift"]?.split("\\s+".toRegex()) ?: listOf("0.0", "0.0")
        val timeParts = m["time"]?.split("\\s+".toRegex()) ?: error("Missing time")
        val rc = m["rc"]?.split("\\s+".toRegex())?.filter { it.isNotBlank() } ?: emptyList()

        return SrdHeader(
            domain = m["domain"] ?: "UNKNOWN",
            sourceRadars = rc,
            time = ZonedDateTime.of(
                timeParts[0].toInt(), timeParts[1].toInt(), timeParts[2].toInt(),
                timeParts[3].toInt(), timeParts[4].toInt(), 0, 0,
                ZoneOffset.UTC
            ),
            width = ncell[0].toInt(),
            height = ncell[1].toInt(),
            cellSizeX = cellsize[0].toDouble(),
            cellSizeY = cellsize[1].toDouble(),
            projection = m["proj"] ?: "UNKNOWN",
            ellipseA = ellipse[0].toDouble(),
            ellipseB = ellipse[1].toDouble(),
            par1 = par[0].toDouble(),
            par2 = par[1].toDouble(),
            originLon = origin[0].toDouble(),
            originLat = origin[1].toDouble(),
            shiftX = shift[0].toDouble(),
            shiftY = shift[1].toDouble(),
            offset = m["offset"]?.toInt() ?: 64,
            start = m["start"]?.toDouble() ?: -8.0,
            slope = m["slope"]?.toDouble() ?: 2.0,
            nodata = m["nodata"]?.toInt() ?: 126,
        )
    }

    private fun parseDataGrid(lines: List<String>, dataStartLine: Int, header: SrdHeader): FloatArray {
        val width = header.width
        val height = header.height
        val values = FloatArray(width * height)

        for (row in 0 until height) {
            val lineIndex = dataStartLine + row
            val line = if (lineIndex < lines.size) lines[lineIndex] else ""
            for (col in 0 until width) {
                val byteVal = if (col < line.length) line[col].code else header.offset
                values[row * width + col] = decodeValue(byteVal, header)
            }
        }

        return values
    }

    private fun decodeValue(byteVal: Int, header: SrdHeader): Float {
        if (byteVal == header.nodata) return Float.NaN
        if (byteVal == header.offset) return 0.0f // '@' = below threshold

        val dbrH = (byteVal - header.offset) * header.slope + header.start
        return 10.0.pow(dbrH / 10.0).toFloat()
    }
}
