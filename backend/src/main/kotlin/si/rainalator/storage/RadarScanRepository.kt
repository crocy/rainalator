package si.rainalator.storage

import si.rainalator.ingestion.RadarScan
import java.time.ZonedDateTime

/**
 * Stores and retrieves radar scans from PostGIS.
 */
interface RadarScanRepository {

    fun insert(scan: RadarScan)

    /**
     * Scans in the half-open period `[from, to)`. A scan carries the rate for the interval
     * *starting* at its timestamp, so one landing exactly on [to] describes rain falling
     * after the period ends and is excluded.
     */
    fun findScanTimes(from: ZonedDateTime, to: ZonedDateTime): List<ZonedDateTime>

    fun countScans(): Long
}
