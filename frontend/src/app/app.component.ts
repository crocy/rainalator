import { Component, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MapComponent } from './components/map/map.component';
import { QueryPanelComponent } from './components/query-panel/query-panel.component';
import { TimelineComponent } from './components/timeline/timeline.component';
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

  currentPolygon: string | null = null;
  scanTimes: string[] = [];

  onPolygonDrawn(wkt: string) {
    this.currentPolygon = wkt;
  }

  onPolygonCleared() {
    this.currentPolygon = null;
    this.scanTimes = [];
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

  onScanSelected(timestamp: string) {
    const url = `/api/rainfall/overlay/${encodeURIComponent(timestamp)}`;
    // Fetch the overlay and get bounds from headers
    fetch(url).then(resp => {
      if (!resp.ok) return;
      const bounds = {
        south: parseFloat(resp.headers.get('X-Bounds-South') || '0'),
        west: parseFloat(resp.headers.get('X-Bounds-West') || '0'),
        north: parseFloat(resp.headers.get('X-Bounds-North') || '0'),
        east: parseFloat(resp.headers.get('X-Bounds-East') || '0'),
      };
      resp.blob().then(blob => {
        const objectUrl = URL.createObjectURL(blob);
        this.mapComponent?.setOverlay(objectUrl, bounds);
      });
    });
  }
}
