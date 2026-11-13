/** Colour scale the overlay is painted with. Per-scan frames are always `rate`. */
export type ScaleMode = 'rate' | 'fixed' | 'autofit';

export interface RainfallQueryRequest {
  polygon: string;
  from: string;
  to: string;
  /** Omitted for per-scan frames, so the server anchors frames on real scan times. */
  bucketMinutes?: number;
}

export interface BucketStats {
  start: string;
  end: string;
  depthMm: number;
  meanRateMmH: number;
  minRateMmH: number;
  maxRateMmH: number;
  volumeM3: number;
  scanCount: number;
  /** The oldest bucket is clipped to the window start, so it can cover less than its nominal span. */
  partial: boolean;
}

export interface RainfallQueryResponse {
  buckets: BucketStats[];
  /** Area-averaged depth in mm — independent of how large the selected polygon is. */
  accumulatedRainfallMm: number;
  /** Total water collected over the polygon in m³ — grows with the selected area. */
  totalVolumeM3: number;
  areaKm2: number;
  bucketCount: number;
  /** Echoes 5 when un-bucketed. */
  bucketMinutes: number;
}

/** One step of the timeline: a single scan un-bucketed, a whole bucket otherwise. */
export interface Frame {
  start: string;
  end: string;
  partial: boolean;
}

export interface FramesResponse {
  frames: Frame[];
  count: number;
  bucketMinutes: number;
  /** Non-null only for `scale=autofit`; every frame in the window shares it. */
  scaleMaxMm: number | null;
}

/** Frames plus the scale they must be painted with, so overlays across one window stay comparable. */
export interface FrameSequence {
  frames: Frame[];
  scale: ScaleMode;
  scaleMaxMm: number | null;
}

/** Read off the overlay response, so the legend can never disagree with the painted image. */
export interface RainfallLegend {
  unit: string;
  /** Ascending band boundaries; there is always one more colour than threshold. */
  thresholds: number[];
  colors: string[];
}
