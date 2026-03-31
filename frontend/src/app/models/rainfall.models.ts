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
  accumulatedRainfallMm: number;
  scanCount: number;
  intervalMinutes: number;
}

export interface ScanTimesResponse {
  scanTimes: string[];
  count: number;
}
