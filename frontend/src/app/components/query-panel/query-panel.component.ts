import { Component, ElementRef, EventEmitter, Input, Output, inject, viewChildren } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RainfallService } from '../../services/rainfall.service';
import {
  BucketStats,
  FrameSequence,
  RainfallQueryRequest,
  RainfallQueryResponse,
  ScaleMode,
} from '../../models/rainfall.models';
import { polygonAreaKm2 } from '../../utils/polygon-area';
import { describeMinutes, formatClockTime, formatFramePeriod } from '../../utils/time-format';

type IntervalTab = 'last' | 'range';
type TimeUnit = 'minutes' | 'hours' | 'days';

const MINUTES_PER_UNIT: Record<TimeUnit, number> = { minutes: 1, hours: 60, days: 1440 };
const SCAN_INTERVAL_MINUTES = 5;

@Component({
  selector: 'app-query-panel',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="panel-header">
      <h2>Rainfall Analysis</h2>
    </div>

    <div class="panel-body">
      <div class="tabs" role="tablist" aria-label="Time interval" (keydown)="onTabKeydown($event)">
        <button
          #tabBtn
          class="tab"
          role="tab"
          id="tab-last"
          type="button"
          aria-controls="tab-panel-last"
          [class.active]="activeTab === 'last'"
          [attr.aria-selected]="activeTab === 'last'"
          [attr.tabindex]="activeTab === 'last' ? 0 : -1"
          (click)="selectTab('last')"
        >
          Last
        </button>
        <button
          #tabBtn
          class="tab"
          role="tab"
          id="tab-range"
          type="button"
          aria-controls="tab-panel-range"
          [class.active]="activeTab === 'range'"
          [attr.aria-selected]="activeTab === 'range'"
          [attr.tabindex]="activeTab === 'range' ? 0 : -1"
          (click)="selectTab('range')"
        >
          From – To
        </button>
      </div>

      <div
        class="tab-panel tab-panel-last"
        id="tab-panel-last"
        role="tabpanel"
        aria-labelledby="tab-last"
        *ngIf="activeTab === 'last'"
      >
        <div class="form-group">
          <label for="last-amount">Last</label>
          <div class="amount-row">
            <input
              id="last-amount"
              type="number"
              min="1"
              step="1"
              [ngModel]="lastAmount"
              (ngModelChange)="lastAmount = $event; onRangeChanged()"
            />
            <select
              class="unit-select"
              aria-label="Window unit"
              [ngModel]="lastUnit"
              (ngModelChange)="lastUnit = $event; onRangeChanged()"
            >
              <option *ngFor="let unit of timeUnits" [value]="unit">{{ unit }}</option>
            </select>
          </div>
        </div>
      </div>

      <div
        class="tab-panel tab-panel-range"
        id="tab-panel-range"
        role="tabpanel"
        aria-labelledby="tab-range"
        *ngIf="activeTab === 'range'"
      >
        <div class="form-group">
          <label>From</label>
          <div class="datetime-row">
            <input type="date" aria-label="From date" [ngModel]="fromDatePart"
                   (ngModelChange)="fromDatePart = $event; onRangeChanged()" />
            <input type="time" aria-label="From time" [ngModel]="fromTimePart"
                   (ngModelChange)="fromTimePart = $event; onRangeChanged()" />
          </div>
        </div>

        <div class="form-group">
          <label>To</label>
          <div class="datetime-row">
            <input type="date" aria-label="To date" [ngModel]="toDatePart"
                   (ngModelChange)="toDatePart = $event; onRangeChanged()" />
            <input type="time" aria-label="To time" [ngModel]="toTimePart"
                   (ngModelChange)="toTimePart = $event; onRangeChanged()" />
          </div>
        </div>
      </div>

      <!-- Outside the tabs: the bucket size applies to whichever window is selected. -->
      <div class="form-group bucket-control">
        <label for="bucket-amount">Bucket</label>
        <div class="amount-row">
          <input
            id="bucket-amount"
            type="number"
            [min]="bucketStep"
            [step]="bucketStep"
            [ngModel]="bucketAmount"
            (ngModelChange)="bucketAmount = $event; onBucketChanged()"
          />
          <select
            class="unit-select"
            aria-label="Bucket unit"
            [ngModel]="bucketUnit"
            (ngModelChange)="bucketUnit = $event; onBucketChanged()"
          >
            <option *ngFor="let unit of timeUnits" [value]="unit">{{ unit }}</option>
          </select>
        </div>
        <span class="bucket-hint" *ngIf="!bucketingEnabled && !bucketError">
          One frame per scan.
        </span>
      </div>

      <div class="form-group" *ngIf="bucketingEnabled">
        <label for="scale-mode">Colour scale</label>
        <select
          id="scale-mode"
          class="scale-select"
          [ngModel]="scaleMode"
          (ngModelChange)="scaleMode = $event; onBucketChanged()"
        >
          <option value="fixed">Fixed mm</option>
          <option value="autofit">Auto-fit</option>
          <option value="rate">Rate-equivalent</option>
        </select>
      </div>

      <div class="validation" role="alert" *ngIf="bucketError || windowError">
        <span class="bucket-error" *ngIf="bucketError">{{ bucketError }}</span>
        <span class="window-error" *ngIf="windowError">{{ windowError }}</span>
      </div>

      <button
        class="load-radar-btn"
        [disabled]="loadingFrames || !canQuery"
        (click)="loadRadar()"
      >
        {{ loadingFrames ? 'Loading...' : 'Load Radar' }}
      </button>

      <p *ngIf="!polygon" class="no-polygon-hint">
        Draw a polygon on the map for rainfall analysis.
      </p>

      <div *ngIf="polygon" class="analysis-scope">
        <span class="scope-heading">Measured across</span>
        <span class="scope-terms">
          <span class="scope-term">{{ selectedAreaKm2 | number:'1.0-0' }} km²</span>
          <span class="scope-joiner">over</span>
          <span class="scope-term">{{ selectedDurationLabel }}{{ bucketClauseLabel ? ',' : '' }}</span>
          <span class="scope-bucket-clause" *ngIf="bucketClauseLabel">{{ bucketClauseLabel }}</span>
        </span>
      </div>

      <button
        class="analyze-btn"
        [disabled]="!polygon || loading || !canQuery"
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

        <div class="collected">
          <span class="value">{{ formatVolume(result.totalVolumeM3) }}</span>
          <span class="label">Total Water Collected</span>
        </div>

        <div class="meta">{{ resultMetaLabel }}</div>

        <div style="overflow-x: auto;">
          <table class="bucket-table">
            <thead>
              <tr>
                <th>Period</th>
                <th>Depth (mm)</th>
                <th>Mean (mm/h)</th>
                <th>Peak (mm/h)</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let bucket of result.buckets" [class.partial]="bucket.partial">
                <td>
                  {{ formatBucketPeriod(bucket) }}
                  <span
                    class="partial-badge"
                    *ngIf="bucket.partial"
                    title="Clipped to the start of the selected period, so it covers less time than the others."
                  >partial</span>
                </td>
                <td>{{ bucket.depthMm | number:'1.2-2' }}</td>
                <td>{{ bucket.meanRateMmH | number:'1.2-2' }}</td>
                <td>{{ bucket.maxRateMmH | number:'1.2-2' }}</td>
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
  @Input()
  set polygon(value: string | null) {
    if (value === this.drawnPolygon) return;
    this.drawnPolygon = value;
    this.selectedAreaKm2 = value ? polygonAreaKm2(value) : 0;
    this.discardStaleResult();
  }

  get polygon(): string | null {
    return this.drawnPolygon;
  }

  @Output() queryResult = new EventEmitter<RainfallQueryResponse>();
  @Output() framesLoaded = new EventEmitter<FrameSequence>();

  private rainfallService = inject(RainfallService);
  private tabButtons = viewChildren<ElementRef<HTMLButtonElement>>('tabBtn');
  private drawnPolygon: string | null = null;
  private windowSnapshot: { from: Date; to: Date } | null = null;

  readonly timeUnits: TimeUnit[] = ['minutes', 'hours', 'days'];

  selectedAreaKm2 = 0;

  activeTab: IntervalTab = 'last';
  lastAmount = 2;
  lastUnit: TimeUnit = 'hours';

  fromDatePart = '';
  fromTimePart = '';
  toDatePart = '';
  toTimePart = '';

  bucketAmount = SCAN_INTERVAL_MINUTES;
  bucketUnit: TimeUnit = 'minutes';
  /** Remembered across un-bucketed loads; only takes effect once bucketing is on. */
  scaleMode: ScaleMode = 'fixed';

  loading = false;
  loadingFrames = false;
  error: string | null = null;
  result: RainfallQueryResponse | null = null;

  constructor() {
    // Seeds the range fields only. Deliberately not currentWindow(): snapshotting here would
    // pin `now` to page-load time, so a tab left open for an hour would query a stale window.
    this.applyWindowToRangeFields(this.resolveWindow());
  }

  get bucketMinutes(): number {
    return this.bucketAmount * MINUTES_PER_UNIT[this.bucketUnit];
  }

  get bucketingEnabled(): boolean {
    return this.bucketMinutes > SCAN_INTERVAL_MINUTES;
  }

  /** Smallest valid bucket in the chosen unit, which is also its increment: minutes have to
   *  land on the scan interval, while any whole hour or day already does. */
  get bucketStep(): number {
    return this.bucketUnit === 'minutes' ? SCAN_INTERVAL_MINUTES : 1;
  }

  /** Per-scan frames carry rates, so only a bucketed window can honour the user's scale choice. */
  get effectiveScale(): ScaleMode {
    return this.bucketingEnabled ? this.scaleMode : 'rate';
  }

  /** Mirrors the server's rules so a bad bucket never becomes a round trip and a 400. */
  get bucketError(): string | null {
    const minutes = this.bucketMinutes;
    if (!Number.isFinite(minutes) || minutes < SCAN_INTERVAL_MINUTES) {
      return 'Bucket must be at least 5 minutes.';
    }
    if (minutes % SCAN_INTERVAL_MINUTES !== 0) {
      return 'Bucket must be a multiple of 5 minutes.';
    }
    // Do not blame the bucket for a window that is unusable in its own right.
    const window = this.selectedWindowMinutes;
    if (window > 0 && minutes > window) {
      return 'Bucket must not be longer than the selected period.';
    }
    return null;
  }

  /** A blank or reversed window would otherwise reach `toISOString()` as an Invalid Date. */
  get windowError(): string | null {
    if (this.selectedWindowMinutes > 0) return null;
    return this.activeTab === 'last'
      ? 'Enter how far back to look.'
      : 'Select a period that ends after it starts.';
  }

  get canQuery(): boolean {
    return !this.bucketError && !this.windowError;
  }

  selectTab(tab: IntervalTab): void {
    if (tab === this.activeTab) return;

    if (tab === 'range') {
      // Re-derived from the minute-resolution fields just written, so what the user reads
      // is what gets queried. Loaded frames keep the pre-switch seconds; truncating `now`
      // instead would drop a scan landing on `to`, since membership is half-open.
      this.applyWindowToRangeFields(this.currentWindow());
      this.activeTab = tab;
      this.windowSnapshot = this.resolveWindow();
    } else {
      this.activeTab = tab;
      this.invalidateWindow();
    }

    this.discardStaleResult();
  }

  onTabKeydown(event: KeyboardEvent): void {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
    event.preventDefault();
    this.selectTab(this.activeTab === 'last' ? 'range' : 'last');
    const index = this.activeTab === 'last' ? 0 : 1;
    this.tabButtons()[index]?.nativeElement.focus();
  }

  loadRadar(): void {
    if (!this.canQuery) return;

    this.loadingFrames = true;
    this.error = null;

    const { from, to } = this.currentWindow();
    const scale = this.effectiveScale;

    this.rainfallService
      .getFrames(
        from.toISOString(),
        to.toISOString(),
        this.bucketingEnabled ? this.bucketMinutes : undefined,
        this.bucketingEnabled ? scale : undefined
      )
      .subscribe({
        next: (response) => {
          this.framesLoaded.emit({
            frames: response.frames,
            scale,
            scaleMaxMm: response.scaleMaxMm ?? null,
          });
          this.loadingFrames = false;
        },
        error: (err) => {
          this.error = this.messageFrom(err, 'Failed to load frames.');
          this.loadingFrames = false;
        },
      });
  }

  analyze(): void {
    if (!this.polygon || !this.canQuery) return;

    this.loading = true;
    this.error = null;
    this.result = null;

    const { from, to } = this.currentWindow();
    const request: RainfallQueryRequest = {
      polygon: this.polygon,
      from: from.toISOString(),
      to: to.toISOString(),
    };
    if (this.bucketingEnabled) request.bucketMinutes = this.bucketMinutes;

    this.rainfallService.queryRainfall(request).subscribe({
      next: (response) => {
        this.result = response;
        this.loading = false;
        this.queryResult.emit(response);
      },
      error: (err) => {
        this.error = this.messageFrom(err, 'Analysis failed. Please try again.');
        this.loading = false;
      },
    });
  }

  onRangeChanged(): void {
    this.invalidateWindow();
    this.discardStaleResult();
  }

  /** Bucket size regroups the window; it does not move its edges, so the snapshot survives. */
  onBucketChanged(): void {
    this.discardStaleResult();
  }

  /** The scope banner tracks the live selection, so metrics from an older one would contradict it. */
  private discardStaleResult(): void {
    this.result = null;
  }

  /** Describes the selected window in whichever unit reads most naturally. */
  get selectedDurationLabel(): string {
    return describeMinutes(this.selectedWindowMinutes) ?? 'the selected period';
  }

  get bucketClauseLabel(): string {
    if (!this.bucketingEnabled) return '';
    const size = describeMinutes(this.bucketMinutes);
    const count = this.selectedBucketCount;
    if (!size || count <= 0) return '';
    return `in ${count} bucket${count === 1 ? '' : 's'} of ${size}`;
  }

  get resultMetaLabel(): string {
    if (!this.result) return '';
    const { bucketCount, bucketMinutes } = this.result;
    const noun = bucketMinutes > SCAN_INTERVAL_MINUTES ? 'bucket' : 'frame';
    return `${bucketCount} ${noun}${bucketCount === 1 ? '' : 's'} of ${describeMinutes(bucketMinutes)}`;
  }

  formatBucketPeriod(bucket: BucketStats): string {
    const start = new Date(bucket.start);
    const spanMinutes = (new Date(bucket.end).getTime() - start.getTime()) / 60_000;
    // A single scan is pinned to the window the user just picked, so the date adds nothing.
    if (spanMinutes <= SCAN_INTERVAL_MINUTES) return formatClockTime(start);
    return formatFramePeriod(bucket.start, bucket.end);
  }

  /** Volumes span m³ to billions across polygon sizes, so pick a magnitude that stays readable. */
  formatVolume(m3: number): string {
    if (m3 >= 1e9) return `${(m3 / 1e9).toFixed(2)} billion m³`;
    if (m3 >= 1e6) return `${(m3 / 1e6).toFixed(1)} million m³`;
    if (m3 >= 1e3) return `${(m3 / 1e3).toFixed(1)} thousand m³`;
    return `${Math.round(m3)} m³`;
  }

  private get selectedWindowMinutes(): number {
    if (this.activeTab === 'last') {
      return this.lastAmount * MINUTES_PER_UNIT[this.lastUnit];
    }
    const from = new Date(`${this.fromDatePart}T${this.fromTimePart}`).getTime();
    const to = new Date(`${this.toDatePart}T${this.toTimePart}`).getTime();
    return (to - from) / 60_000;
  }

  /** Buckets walk back from the window end, so a remainder still costs a (partial) bucket. */
  private get selectedBucketCount(): number {
    return Math.ceil(this.selectedWindowMinutes / this.bucketMinutes);
  }

  /**
   * The one window every query for the current selection uses. Bucket edges anchor at the
   * window end, so if Load Radar and Analyze each resolved their own `now` the timeline and
   * the results table would describe different buckets for the same "last 2 hours".
   * Resolved lazily rather than up front, or a tab left open would query page-load time.
   */
  private currentWindow(): { from: Date; to: Date } {
    this.windowSnapshot ??= this.resolveWindow();
    return this.windowSnapshot;
  }

  /** Called wherever the user changes what the window *means*, so `now` resolves afresh. */
  private invalidateWindow(): void {
    this.windowSnapshot = null;
  }

  /** `now` resolves here, at click time — the relative window never auto-refreshes. */
  private resolveWindow(): { from: Date; to: Date } {
    if (this.activeTab === 'last') {
      const to = new Date();
      const minutes = this.lastAmount * MINUTES_PER_UNIT[this.lastUnit];
      return { from: new Date(to.getTime() - minutes * 60_000), to };
    }
    return {
      from: new Date(`${this.fromDatePart}T${this.fromTimePart}`),
      to: new Date(`${this.toDatePart}T${this.toTimePart}`),
    };
  }

  private applyWindowToRangeFields(window: { from: Date; to: Date }): void {
    this.fromDatePart = toDatePart(window.from);
    this.fromTimePart = toTimePart(window.from);
    this.toDatePart = toDatePart(window.to);
    this.toTimePart = toTimePart(window.to);
  }

  /** The API reports validation failures as `{ error }`; fall back for transport-level failures. */
  private messageFrom(err: unknown, fallback: string): string {
    const httpError = err as { error?: { error?: string; message?: string }; message?: string };
    return httpError?.error?.error || httpError?.error?.message || httpError?.message || fallback;
  }
}

function toDatePart(date: Date): string {
  const pad = (n: number) => n.toString().padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function toTimePart(date: Date): string {
  const pad = (n: number) => n.toString().padStart(2, '0');
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
