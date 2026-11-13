import { Component, inject, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MapComponent } from './components/map/map.component';
import { QueryPanelComponent } from './components/query-panel/query-panel.component';
import { TimelineComponent } from './components/timeline/timeline.component';
import { LegendComponent } from './components/legend/legend.component';
import { RainfallService } from './services/rainfall.service';
import {
  Frame,
  FrameSequence,
  RainfallLegend,
  RainfallQueryResponse,
  ScaleMode,
} from './models/rainfall.models';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, MapComponent, QueryPanelComponent, TimelineComponent, LegendComponent],
  template: `
    <div class="layout">
      <aside class="sidebar">
        <app-query-panel
          [polygon]="currentPolygon"
          (queryResult)="onQueryResult($event)"
          (framesLoaded)="onFramesLoaded($event)"
        />
      </aside>
      <main class="map-area">
        <app-map
          (polygonDrawn)="onPolygonDrawn($event)"
          (polygonCleared)="onPolygonCleared()"
        />
        <div class="overlay-loading" *ngIf="overlayLoading">Loading overlay...</div>
        <div class="overlay-error" *ngIf="overlayError">{{ overlayError }}</div>
        <app-legend class="legend-overlay" *ngIf="legend" [legend]="legend" />
        <div class="timeline-overlay" *ngIf="frames.length > 0">
          <app-timeline
            [frames]="frames"
            (frameSelected)="onFrameSelected($event)"
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
  frames: Frame[] = [];
  legend: RainfallLegend | null = null;
  overlayLoading = false;
  overlayError: string | null = null;

  /** Every frame in a sequence shares a scale, so overlays across the window stay comparable. */
  private overlayScale: ScaleMode = 'rate';
  private scaleMaxMm: number | null = null;

  onPolygonDrawn(wkt: string) {
    this.currentPolygon = wkt;
  }

  onPolygonCleared() {
    this.currentPolygon = null;
    this.overlayError = null;
  }

  onQueryResult(result: RainfallQueryResponse) {
    // Result is handled by the query panel display
  }

  onFramesLoaded(sequence: FrameSequence) {
    this.frames = sequence.frames;
    this.overlayScale = sequence.scale;
    this.scaleMaxMm = sequence.scaleMaxMm;
    // The old legend describes the old scale; the first overlay of this sequence replaces it.
    this.legend = null;
    if (this.frames.length > 0) {
      this.onFrameSelected(this.frames[0]);
    }
  }

  async onFrameSelected(frame: Frame) {
    const url = this.rainfallService.getOverlayUrl(frame, this.overlayScale, this.scaleMaxMm);
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

      this.legend = this.rainfallService.parseLegend(resp.headers);

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
