import {
  Component,
  EventEmitter,
  Input,
  OnDestroy,
  Output,
} from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-timeline',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="timeline-bar" *ngIf="scanTimes.length > 0">
      <span class="timestamp">{{ formatTimestamp(scanTimes[currentIndex]) }}</span>

      <input
        type="range"
        class="slider"
        [min]="0"
        [max]="scanTimes.length - 1"
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

      <span class="scan-count">{{ currentIndex + 1 }} / {{ scanTimes.length }}</span>
    </div>
  `,
  styleUrl: './timeline.component.scss',
})
export class TimelineComponent implements OnDestroy {
  @Input() scanTimes: string[] = [];
  @Output() scanSelected = new EventEmitter<string>();

  currentIndex = 0;
  playing = false;
  playInterval: any = null;

  onSliderChange(event: Event): void {
    const target = event.target as HTMLInputElement;
    this.currentIndex = Number(target.value);
    this.emitCurrent();
  }

  previous(): void {
    if (this.scanTimes.length === 0) return;
    this.currentIndex =
      this.currentIndex > 0 ? this.currentIndex - 1 : this.scanTimes.length - 1;
    this.emitCurrent();
  }

  next(): void {
    if (this.scanTimes.length === 0) return;
    this.currentIndex = (this.currentIndex + 1) % this.scanTimes.length;
    this.emitCurrent();
  }

  togglePlay(): void {
    if (this.playing) {
      this.stopPlay();
    } else {
      this.startPlay();
    }
  }

  formatTimestamp(iso: string): string {
    if (!iso) return '';
    const d = new Date(iso);
    const hh = d.getHours().toString().padStart(2, '0');
    const mm = d.getMinutes().toString().padStart(2, '0');
    const dd = d.getDate().toString().padStart(2, '0');
    const mo = (d.getMonth() + 1).toString().padStart(2, '0');
    return `${hh}:${mm} ${dd}/${mo}`;
  }

  ngOnDestroy(): void {
    this.stopPlay();
  }

  private startPlay(): void {
    this.playing = true;
    this.playInterval = setInterval(() => {
      this.next();
    }, 500);
  }

  private stopPlay(): void {
    this.playing = false;
    if (this.playInterval !== null) {
      clearInterval(this.playInterval);
      this.playInterval = null;
    }
  }

  private emitCurrent(): void {
    if (this.scanTimes.length > 0) {
      this.scanSelected.emit(this.scanTimes[this.currentIndex]);
    }
  }
}
