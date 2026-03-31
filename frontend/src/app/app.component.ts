import { Component, inject, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MapComponent } from './components/map/map.component';
import { QueryPanelComponent } from './components/query-panel/query-panel.component';
import { TimelineComponent } from './components/timeline/timeline.component';
import { RainfallService } from './services/rainfall.service';
import { RainfallQueryResponse } from './models/rainfall.models';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, MapComponent, QueryPanelComponent, TimelineComponent],
  template: `
    <div class="layout">
      <aside class="sidebar">
        <app-query-panel
          [polygon]="currentPolygon"
          (queryResult)="onQueryResult($event)"
          (scanTimesLoaded)="onScanTimesLoaded($event)"
        />
      </aside>
      <main class="map-area">
        <app-map
          (polygonDrawn)="onPolygonDrawn($event)"
          (polygonCleared)="onPolygonCleared()"
        />
        <div class="overlay-loading" *ngIf="overlayLoading">Loading overlay...</div>
        <div class="overlay-error" *ngIf="overlayError">{{ overlayError }}</div>
        <div class="timeline-overlay" *ngIf="scanTimes.length > 0">
          <app-timeline
            [scanTimes]="scanTimes"
            (scanSelected)="onScanSelected($event)"
          />
        </div>
      </main>
    </div>
  `,
  styleUrl: './app.component.scss'
})
export class AppComponent {
  @ViewChild(MapComponent) mapComponent!: MapComponent;

  private rainfallService = inject(RainfallService);
  private currentBlobUrl: string | null = null;

  currentPolygon: string | null = null;
  scanTimes: string[] = [];
  overlayLoading = false;
  overlayError: string | null = null;

  onPolygonDrawn(wkt: string) {
    this.currentPolygon = wkt;
  }

  onPolygonCleared() {
    this.currentPolygon = null;
    this.scanTimes = [];
    this.overlayError = null;
    this.revokeCurrentBlob();
    this.mapComponent?.clearOverlay();
  }

  onQueryResult(result: RainfallQueryResponse) {
    // Result is handled by the query panel display
  }

  onScanTimesLoaded(times: string[]) {
    this.scanTimes = times;
    if (times.length > 0) {
      this.onScanSelected(times[0]);
    }
  }

  async onScanSelected(timestamp: string) {
    const url = this.rainfallService.getOverlayUrl(timestamp);
    this.overlayLoading = true;
    this.overlayError = null;

    try {
      const resp = await fetch(url);
      if (!resp.ok) {
        this.overlayError = `Overlay failed (HTTP ${resp.status})`;
        this.overlayLoading = false;
        return;
      }

      const south = resp.headers.get('X-Bounds-South');
      const west = resp.headers.get('X-Bounds-West');
      const north = resp.headers.get('X-Bounds-North');
      const east = resp.headers.get('X-Bounds-East');

      if (!south || !west || !north || !east) {
        this.overlayError = 'Missing bounds in overlay response';
        this.overlayLoading = false;
        return;
      }

      const bounds = {
        south: parseFloat(south),
        west: parseFloat(west),
        north: parseFloat(north),
        east: parseFloat(east),
      };

      const blob = await resp.blob();
      this.revokeCurrentBlob();
      this.currentBlobUrl = URL.createObjectURL(blob);
      this.mapComponent?.setOverlay(this.currentBlobUrl, bounds);
    } catch (e) {
      this.overlayError = 'Failed to load overlay';
    } finally {
      this.overlayLoading = false;
    }
  }

  private revokeCurrentBlob() {
    if (this.currentBlobUrl) {
      URL.revokeObjectURL(this.currentBlobUrl);
      this.currentBlobUrl = null;
    }
  }
}
