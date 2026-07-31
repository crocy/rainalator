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
        <label>From</label>
        <div class="datetime-row">
          <input type="date" [(ngModel)]="fromDatePart" />
          <input type="time" [(ngModel)]="fromTimePart" />
        </div>
      </div>

      <div class="form-group">
        <label>To</label>
        <div class="datetime-row">
          <input type="date" [(ngModel)]="toDatePart" />
          <input type="time" [(ngModel)]="toTimePart" />
        </div>
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
          <span class="label">
            Accumulated Rainfall over
            {{ result.areaKm2 | number:'1.0-0' }} km² in {{ selectedDurationLabel }}
          </span>
        </div>

        <div class="collected">
          <span class="value">{{ formatVolume(result.totalVolumeM3) }}</span>
          <span class="label">Total Water Collected</span>
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

  fromDatePart = '';
  fromTimePart = '';
  toDatePart = '';
  toTimePart = '';
  loading = false;
  loadingScans = false;
  error: string | null = null;
  result: RainfallQueryResponse | null = null;

  constructor() {
    const now = new Date();
    const twoHoursAgo = new Date(now.getTime() - 2 * 60 * 60 * 1000);
    this.toDatePart = this.toDatePart_(now);
    this.toTimePart = this.toTimePart_(now);
    this.fromDatePart = this.toDatePart_(twoHoursAgo);
    this.fromTimePart = this.toTimePart_(twoHoursAgo);
  }

  loadRadar(): void {
    this.loadingScans = true;
    this.error = null;

    const fromISO = this.combinedISO(this.fromDatePart, this.fromTimePart);
    const toISO = this.combinedISO(this.toDatePart, this.toTimePart);

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

    const fromISO = this.combinedISO(this.fromDatePart, this.fromTimePart);
    const toISO = this.combinedISO(this.toDatePart, this.toTimePart);

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

  /** Describes the FROM/TO span in whichever unit reads most naturally. */
  get selectedDurationLabel(): string {
    const from = new Date(`${this.fromDatePart}T${this.fromTimePart}`).getTime();
    const to = new Date(`${this.toDatePart}T${this.toTimePart}`).getTime();
    const minutes = Math.round((to - from) / 60000);
    if (!Number.isFinite(minutes) || minutes <= 0) return 'the selected period';

    const plural = (n: number, unit: string) => `${n} ${unit}${n === 1 ? '' : 's'}`;
    if (minutes < 60) return plural(minutes, 'minute');
    if (minutes % 1440 !== 0 || minutes < 1440) return plural(Math.round(minutes / 60), 'hour');
    return plural(minutes / 1440, 'day');
  }

  /** Volumes span m³ to billions across polygon sizes, so pick a magnitude that stays readable. */
  formatVolume(m3: number): string {
    if (m3 >= 1e9) return `${(m3 / 1e9).toFixed(2)} billion m³`;
    if (m3 >= 1e6) return `${(m3 / 1e6).toFixed(1)} million m³`;
    if (m3 >= 1e3) return `${(m3 / 1e3).toFixed(1)} thousand m³`;
    return `${Math.round(m3)} m³`;
  }

  private toDatePart_(d: Date): string {
    const pad = (n: number) => n.toString().padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  }

  private toTimePart_(d: Date): string {
    const pad = (n: number) => n.toString().padStart(2, '0');
    return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  private combinedISO(datePart: string, timePart: string): string {
    return new Date(`${datePart}T${timePart}`).toISOString();
  }
}
