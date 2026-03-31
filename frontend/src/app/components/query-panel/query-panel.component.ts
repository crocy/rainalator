import { Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RainfallService } from '../../services/rainfall.service';
import { RainfallQueryResponse } from '../../models/rainfall.models';

@Component({
  selector: 'app-query-panel',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="panel-header">
      <h2>Rainfall Analysis</h2>
    </div>

    <div class="panel-body">
      <div class="form-group">
        <label for="fromDate">From</label>
        <input
          type="datetime-local"
          id="fromDate"
          [(ngModel)]="fromDate"
        />
      </div>

      <div class="form-group">
        <label for="toDate">To</label>
        <input
          type="datetime-local"
          id="toDate"
          [(ngModel)]="toDate"
        />
      </div>

      <button
        class="load-radar-btn"
        [disabled]="loadingScans"
        (click)="loadRadar()"
      >
        {{ loadingScans ? 'Loading...' : 'Load Radar' }}
      </button>

      <p *ngIf="!polygon" class="no-polygon-hint">
        Draw a polygon on the map for rainfall analysis.
      </p>

      <button
        class="analyze-btn"
        [disabled]="!polygon || loading"
        (click)="analyze()"
      >
        {{ loading ? 'Analyzing...' : 'Analyze' }}
      </button>

      <div *ngIf="loading" class="spinner">Loading...</div>

      <div *ngIf="error" class="error">{{ error }}</div>

      <div *ngIf="result" class="result-section">
        <div class="accumulated">
          <span class="value">{{ result.accumulatedRainfallMm | number:'1.2-2' }}</span>
          <span class="unit">mm</span>
          <span class="label">Accumulated Rainfall</span>
        </div>

        <div class="meta">
          {{ result.scanCount }} scans, {{ result.intervalMinutes }}-min interval
        </div>

        <div style="overflow-x: auto;">
          <table class="scan-table">
            <thead>
              <tr>
                <th>Time</th>
                <th>Mean (mm/h)</th>
                <th>Min</th>
                <th>Max</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let scan of result.scans">
                <td>{{ scan.scanTime | date:'HH:mm' }}</td>
                <td>{{ scan.mean | number:'1.2-2' }}</td>
                <td>{{ scan.min | number:'1.2-2' }}</td>
                <td>{{ scan.max | number:'1.2-2' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  `,
  styleUrl: './query-panel.component.scss',
})
export class QueryPanelComponent {
  @Input() polygon: string | null = null;
  @Output() queryResult = new EventEmitter<RainfallQueryResponse>();
  @Output() scanTimesLoaded = new EventEmitter<string[]>();

  private rainfallService = inject(RainfallService);

  fromDate = '';
  toDate = '';
  loading = false;
  loadingScans = false;
  error: string | null = null;
  result: RainfallQueryResponse | null = null;

  constructor() {
    const now = new Date();
    const twoHoursAgo = new Date(now.getTime() - 2 * 60 * 60 * 1000);
    this.toDate = this.toDatetimeLocal(now);
    this.fromDate = this.toDatetimeLocal(twoHoursAgo);
  }

  loadRadar(): void {
    this.loadingScans = true;
    this.error = null;

    const fromISO = this.toISO(this.fromDate);
    const toISO = this.toISO(this.toDate);

    this.rainfallService.getScanTimes(fromISO, toISO).subscribe({
      next: (scanTimesResponse) => {
        this.scanTimesLoaded.emit(scanTimesResponse.scanTimes);
        this.loadingScans = false;
      },
      error: (err) => {
        this.error =
          err?.error?.message || err?.message || 'Failed to load scan times.';
        this.loadingScans = false;
      },
    });
  }

  analyze(): void {
    if (!this.polygon) return;

    this.loading = true;
    this.error = null;
    this.result = null;

    const fromISO = this.toISO(this.fromDate);
    const toISO = this.toISO(this.toDate);

    this.rainfallService
      .queryRainfall({ polygon: this.polygon, from: fromISO, to: toISO })
      .subscribe({
        next: (response) => {
          this.result = response;
          this.loading = false;
          this.queryResult.emit(response);
        },
        error: (err) => {
          this.error =
            err?.error?.message || err?.message || 'Analysis failed. Please try again.';
          this.loading = false;
        },
      });
  }

  /** Convert a Date to the `YYYY-MM-DDTHH:mm` format used by datetime-local inputs. */
  private toDatetimeLocal(d: Date): string {
    const pad = (n: number) => n.toString().padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  /** Convert a datetime-local value (`YYYY-MM-DDTHH:mm`) to an ISO-8601 UTC string. */
  private toISO(datetimeLocal: string): string {
    return new Date(datetimeLocal).toISOString();
  }
}
