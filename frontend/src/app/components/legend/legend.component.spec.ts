import { ComponentFixture, TestBed } from '@angular/core/testing';
import { LegendComponent } from './legend.component';

describe('LegendComponent', () => {
  let component: LegendComponent;
  let fixture: ComponentFixture<LegendComponent>;

  const bandLabels = () =>
    Array.from(fixture.nativeElement.querySelectorAll('.band-label')).map((el) =>
      (el as HTMLElement).textContent!.trim()
    );

  const swatchColors = () =>
    Array.from(fixture.nativeElement.querySelectorAll('.swatch')).map(
      (el) => (el as HTMLElement).style.backgroundColor
    );

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LegendComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(LegendComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('renders nothing until an overlay reports its legend', () => {
    expect(fixture.nativeElement.querySelector('.legend')).toBeNull();
  });

  describe('with a legend', () => {
    beforeEach(() => {
      component.legend = {
        unit: 'mm',
        thresholds: [0.2, 0.5, 1],
        colors: ['085AFE', '008CFE', '00AEFD', '00C8FE'],
      };
      fixture.detectChanges();
    });

    it('shows the unit the overlay was painted in', () => {
      const unit: HTMLElement = fixture.nativeElement.querySelector('.legend-unit');
      expect(unit.textContent!.trim()).toBe('mm');
    });

    it('renders one swatch per colour band, strongest first', () => {
      expect(swatchColors()).toEqual([
        'rgb(0, 200, 254)',
        'rgb(0, 174, 253)',
        'rgb(0, 140, 254)',
        'rgb(8, 90, 254)',
      ]);
    });

    it('labels each band with the value it starts at', () => {
      expect(bandLabels()).toEqual(['1', '0.5', '0.2', '0']);
    });

    it('re-renders when the scale changes under it', () => {
      component.legend = {
        unit: 'mm/h',
        thresholds: [0.1, 1],
        colors: ['085AFE', '008CFE', '00AEFD'],
      };
      fixture.detectChanges();

      expect(bandLabels()).toEqual(['1', '0.1', '0']);
      expect(
        (fixture.nativeElement.querySelector('.legend-unit') as HTMLElement).textContent!.trim()
      ).toBe('mm/h');
    });
  });

  // An autofit scale divides the fixed thresholds down, so the low bands land well below 1 mm.
  it('keeps autofit thresholds legible when they fall well below 1', () => {
    component.legend = {
      unit: 'mm',
      thresholds: [0.0427, 0.10675, 0.2135],
      colors: ['085AFE', '008CFE', '00AEFD', '00C8FE'],
    };
    fixture.detectChanges();

    expect(bandLabels()).toEqual(['0.21', '0.11', '0.043', '0']);
  });

  it('drops trailing zeros rather than padding whole millimetres', () => {
    component.legend = {
      unit: 'mm',
      thresholds: [10, 200],
      colors: ['085AFE', '008CFE', '00AEFD'],
    };
    fixture.detectChanges();

    expect(bandLabels()).toEqual(['200', '10', '0']);
  });

  it('drops the legend when the overlay no longer reports one', () => {
    component.legend = {
      unit: 'mm',
      thresholds: [0.2],
      colors: ['085AFE', '008CFE'],
    };
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.legend')).not.toBeNull();

    component.legend = null;
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.legend')).toBeNull();
  });
});
