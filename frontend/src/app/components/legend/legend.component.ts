import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RainfallLegend } from '../../models/rainfall.models';

interface LegendBand {
  color: string;
  /** Value the band starts at — the conventional way to read a colour ramp. */
  label: string;
}

@Component({
  selector: 'app-legend',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="legend" *ngIf="bands.length > 0">
      <span class="legend-unit">{{ unit }}</span>
      <ul class="legend-bands">
        <li class="legend-band" *ngFor="let band of bands">
          <span class="swatch" [style.backgroundColor]="'#' + band.color"></span>
          <span class="band-label">{{ band.label }}</span>
        </li>
      </ul>
    </div>
  `,
  styleUrl: './legend.component.scss',
})
export class LegendComponent {
  @Input()
  set legend(value: RainfallLegend | null) {
    this.unit = value?.unit ?? '';
    this.bands = value ? this.toBands(value) : [];
  }

  unit = '';
  bands: LegendBand[] = [];

  /** Strongest band first, so the ramp reads top-down like the rainfall it describes. */
  private toBands(legend: RainfallLegend): LegendBand[] {
    return legend.colors
      .map((color, index) => ({
        color,
        // The lowest band has no threshold below it; anything above zero lands in it.
        label: formatBandValue(index === 0 ? 0 : legend.thresholds[index - 1]),
      }))
      .reverse();
  }
}

/** Autofit divides the fixed thresholds down, so low bands need more decimals than high ones. */
function formatBandValue(value: number): string {
  let text: string;
  if (value >= 10) text = value.toFixed(0);
  else if (value >= 1) text = value.toFixed(1);
  else if (value >= 0.1) text = value.toFixed(2);
  else text = value.toFixed(3);

  return text.includes('.') ? text.replace(/\.?0+$/, '') : text;
}
