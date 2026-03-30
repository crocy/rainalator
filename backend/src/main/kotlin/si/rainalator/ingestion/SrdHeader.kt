package si.rainalator.ingestion

import java.time.ZonedDateTime

data class SrdHeader(
    val domain: String,
    val sourceRadars: List<String>,
    val time: ZonedDateTime,
    val width: Int,
    val height: Int,
    val cellSizeX: Double,
    val cellSizeY: Double,
    val projection: String,
    val ellipseA: Double,
    val ellipseB: Double,
    val par1: Double,
    val par2: Double,
    val originLon: Double,
    val originLat: Double,
    val shiftX: Double,
    val shiftY: Double,
    val offset: Int,
    val start: Double,
    val slope: Double,
    val nodata: Int,
)
