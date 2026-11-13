import {
  Component,
  EventEmitter,
  Input,
  OnDestroy,
  Output,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { Frame } from '../../models/rainfall.models';
import { formatClockTime, formatDayMonth, formatFramePeriod } from '../../utils/time-format';

const SCAN_INTERVAL_MINUTES = 5;

@Component({
  selector: 'app-timeline',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="timeline-bar" *ngIf="frames.length > 0">
      <span class="timestamp" [class.partial]="frames[currentIndex].partial">
        {{ formatFrame(frames[currentIndex]) }}
      </span>

      <input
        type="range"
        class="slider"
        aria-label="Frame"
        [min]="0"
        [max]="frames.length - 1"
        [value]="currentIndex"
        (input)="onSliderChange($event)"
      />

      <div class="controls">
        <button class="ctrl-btn" (click)="previous()" title="Previous">&#9664;</button>
        <button
          class="ctrl-btn"
          [class.active]="playing"
          (click)="togglePlay()"
          [title]="playing ? 'Pause' : 'Play'"
        >
          {{ playing ? '&#10074;&#10074;' : '&#9654;' }}
        </button>
        <button class="ctrl-btn" (click)="next()" title="Next">&#9654;</button>
      </div>

      <select
        class="speed-select"
        [value]="playbackSpeed"
        (change)="onSpeedChange($event)"
        title="Playback speed"
      >
        <option *ngFor="let s of speedPresets" [value]="s">{{ s }}x</option>
      </select>

      <span class="frame-count">{{ currentIndex + 1 }} / {{ frames.length }}</span>
    </div>
  `,
  styleUrl: './timeline.component.scss',
})
export class TimelineComponent implements OnDestroy {
  @Input()
  set frames(value: Frame[]) {
    this.frameList = value ?? [];
    // A fresh sequence describes a different window; keeping the old offset would mislead.
    this.currentIndex = 0;
  }

  get frames(): Frame[] {
    return this.frameList;
  }

  @Output() frameSelected = new EventEmitter<Frame>();

  readonly speedPresets = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 2.5, 3, 5];
  private readonly BASE_INTERVAL_MS = 500;

  private frameList: Frame[] = [];

  currentIndex = 0;
  playing = false;
  playbackSpeed = 1;
  playInterval: any = null;

  onSliderChange(event: Event): void {
    const target = event.target as HTMLInputElement;
    this.currentIndex = Number(target.value);
    this.emitCurrent();
  }

  previous(): void {
    if (this.frames.length === 0) return;
    this.currentIndex =
      this.currentIndex > 0 ? this.currentIndex - 1 : this.frames.length - 1;
    this.emitCurrent();
  }

  next(): void {
    if (this.frames.length === 0) return;
    this.currentIndex = (this.currentIndex + 1) % this.frames.length;
    this.emitCurrent();
  }

  onSpeedChange(event: Event): void {
    this.playbackSpeed = Number((event.target as HTMLSelectElement).value);
    if (this.playing) {
      this.stopPlay();
      this.startPlay();
    }
  }

  togglePlay(): void {
    if (this.playing) {
      this.stopPlay();
    } else {
      this.startPlay();
    }
  }

  /** A single scan is a moment; a bucket is a span, and the label has to say which. */
  formatFrame(frame: Frame): string {
    if (!frame) return '';
    const start = new Date(frame.start);
    const spanMinutes = (new Date(frame.end).getTime() - start.getTime()) / 60_000;
    if (spanMinutes <= SCAN_INTERVAL_MINUTES) {
      return `${formatClockTime(start)} ${formatDayMonth(start)}`;
    }
    return formatFramePeriod(frame.start, frame.end);
  }

  ngOnDestroy(): void {
    this.stopPlay();
  }

  private startPlay(): void {
    this.playing = true;
    const intervalMs = this.BASE_INTERVAL_MS / this.playbackSpeed;
    this.playInterval = setInterval(() => {
      this.next();
    }, intervalMs);
  }

  private stopPlay(): void {
    this.playing = false;
    if (this.playInterval !== null) {
      clearInterval(this.playInterval);
      this.playInterval = null;
    }
  }

  private emitCurrent(): void {
    if (this.frames.length > 0) {
      this.frameSelected.emit(this.frames[this.currentIndex]);
    }
  }
}
