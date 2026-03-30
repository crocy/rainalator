package si.rainalator.storage

import si.rainalator.ingestion.RadarScan
import java.time.ZonedDateTime

/**
 * Stores and retrieves radar scans from PostGIS.
 */
interface RadarScanRepository {

    fun insert(scan: RadarScan)

    fun findScanTimes(from: ZonedDateTime, to: ZonedDateTime): List<ZonedDateTime>

    fun countScans(): Long
}
