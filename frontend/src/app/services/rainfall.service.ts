import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  Frame,
  FramesResponse,
  RainfallLegend,
  RainfallQueryRequest,
  RainfallQueryResponse,
  ScaleMode,
} from '../models/rainfall.models';

/** Structural stand-in for `Response.headers`, so callers can pass anything header-shaped. */
type HeaderSource = Pick<Headers, 'get'>;

@Injectable({ providedIn: 'root' })
export class RainfallService {
  private http = inject(HttpClient);

  queryRainfall(request: RainfallQueryRequest): Observable<RainfallQueryResponse> {
    return this.http.post<RainfallQueryResponse>('/api/rainfall/query', request);
  }

  getFrames(
    from: string,
    to: string,
    bucketMinutes?: number,
    scale?: ScaleMode
  ): Observable<FramesResponse> {
    let params = new HttpParams().set('from', from).set('to', to);
    if (bucketMinutes != null) params = params.set('bucketMinutes', bucketMinutes);
    if (scale) params = params.set('scale', scale);
    return this.http.get<FramesResponse>('/api/rainfall/frames', { params });
  }

  getOverlayUrl(frame: Frame, scale: ScaleMode, scaleMax: number | null): string {
    const params = new URLSearchParams({ start: frame.start, end: frame.end, scale });
    if (scale === 'autofit' && scaleMax != null) {
      params.set('scaleMax', String(scaleMax));
    }
    return `/api/rainfall/overlay?${params}`;
  }

  /** Null when the headers cannot describe a coherent ramp — better no legend than a wrong one. */
  parseLegend(headers: HeaderSource): RainfallLegend | null {
    const unit = headers.get('X-Legend-Unit');
    const rawThresholds = headers.get('X-Legend-Thresholds');
    const rawColors = headers.get('X-Legend-Colors');
    if (!unit || !rawThresholds || !rawColors) return null;

    const thresholds = rawThresholds.split(',').map((value) => Number(value.trim()));
    const colors = rawColors.split(',').map((color) => color.trim());
    if (thresholds.some((value) => Number.isNaN(value))) return null;
    if (colors.length !== thresholds.length + 1) return null;

    return { unit, thresholds, colors };
  }
}
