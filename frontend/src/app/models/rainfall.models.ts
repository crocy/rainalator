export interface RainfallQueryRequest {
  polygon: string;
  from: string;
  to: string;
}

export interface ScanStats {
  scanTime: string;
  mean: number;
  min: number;
  max: number;
  sum: number;
  count: number;
}

export interface RainfallQueryResponse {
  scans: ScanStats[];
  /** Area-averaged depth in mm — independent of how large the selected polygon is. */
  accumulatedRainfallMm: number;
  /** Total water collected over the polygon in m³ — grows with the selected area. */
  totalVolumeM3: number;
  areaKm2: number;
  scanCount: number;
  intervalMinutes: number;
}

export interface ScanTimesResponse {
  scanTimes: string[];
  count: number;
}
