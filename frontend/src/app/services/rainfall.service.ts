import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  RainfallQueryRequest,
  RainfallQueryResponse,
  ScanTimesResponse,
} from '../models/rainfall.models';

@Injectable({ providedIn: 'root' })
export class RainfallService {
  private http = inject(HttpClient);

  queryRainfall(request: RainfallQueryRequest): Observable<RainfallQueryResponse> {
    return this.http.post<RainfallQueryResponse>('/api/rainfall/query', request);
  }

  getScanTimes(from: string, to: string): Observable<ScanTimesResponse> {
    const params = new HttpParams().set('from', from).set('to', to);
    return this.http.get<ScanTimesResponse>('/api/rainfall/scans', { params });
  }

  getOverlayUrl(timestamp: string): string {
    return `/api/rainfall/overlay/${encodeURIComponent(timestamp)}`;
  }
}
