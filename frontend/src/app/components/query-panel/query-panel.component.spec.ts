import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { QueryPanelComponent } from './query-panel.component';

describe('QueryPanelComponent', () => {
  let component: QueryPanelComponent;
  let fixture: ComponentFixture<QueryPanelComponent>;
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [QueryPanelComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(QueryPanelComponent);
    component = fixture.componentInstance;
    httpTesting = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('analyze button disabled when no polygon', () => {
    component.polygon = null;
    fixture.detectChanges();

    const button: HTMLButtonElement = fixture.nativeElement.querySelector('.analyze-btn');
    expect(button.disabled).toBeTrue();
  });

  it('analyze button enabled when polygon is set', () => {
    component.polygon = 'POLYGON((14.5 46.0,14.6 46.0,14.6 46.1,14.5 46.1,14.5 46.0))';
    component.loading = false;
    fixture.detectChanges();

    const button: HTMLButtonElement = fixture.nativeElement.querySelector('.analyze-btn');
    expect(button.disabled).toBeFalse();
  });

  it('analyze button disabled when loading', () => {
    component.polygon = 'POLYGON((14.5 46.0,14.6 46.0,14.6 46.1,14.5 46.1,14.5 46.0))';
    component.loading = true;
    fixture.detectChanges();

    const button: HTMLButtonElement = fixture.nativeElement.querySelector('.analyze-btn');
    expect(button.disabled).toBeTrue();
  });

  it('load radar button is always enabled', () => {
    component.polygon = null;
    fixture.detectChanges();

    const button: HTMLButtonElement = fixture.nativeElement.querySelector('.load-radar-btn');
    expect(button.disabled).toBeFalse();
  });

  it('load radar button disabled when loading scans', () => {
    component.loadingScans = true;
    fixture.detectChanges();

    const button: HTMLButtonElement = fixture.nativeElement.querySelector('.load-radar-btn');
    expect(button.disabled).toBeTrue();
  });

  describe('duration label', () => {
    const setRange = (fromDate: string, fromTime: string, toDate: string, toTime: string) => {
      component.fromDatePart = fromDate;
      component.fromTimePart = fromTime;
      component.toDatePart = toDate;
      component.toTimePart = toTime;
    };

    it('reports sub-hour ranges in minutes', () => {
      setRange('2024-06-15', '12:00', '2024-06-15', '12:45');
      expect(component.selectedDurationLabel).toBe('45 minutes');
    });

    it('uses the singular for exactly one hour', () => {
      setRange('2024-06-15', '12:00', '2024-06-15', '13:00');
      expect(component.selectedDurationLabel).toBe('1 hour');
    });

    it('reports an exact day as a day', () => {
      setRange('2024-06-15', '00:31', '2024-06-16', '00:31');
      expect(component.selectedDurationLabel).toBe('1 day');
    });

    it('reports a part-day span in hours rather than fractional days', () => {
      setRange('2024-06-15', '00:00', '2024-06-16', '12:00');
      expect(component.selectedDurationLabel).toBe('36 hours');
    });

    it('reports multi-day ranges in days', () => {
      setRange('2024-06-15', '00:00', '2024-06-18', '00:00');
      expect(component.selectedDurationLabel).toBe('3 days');
    });
  });

  describe('volume formatting', () => {
    it('shows raw cubic metres below a thousand', () => {
      expect(component.formatVolume(850)).toBe('850 m³');
    });

    it('scales to thousands', () => {
      expect(component.formatVolume(12_300)).toBe('12.3 thousand m³');
    });

    it('scales to millions', () => {
      expect(component.formatVolume(372_400_000)).toBe('372.4 million m³');
    });

    it('scales to billions', () => {
      expect(component.formatVolume(2_500_000_000)).toBe('2.50 billion m³');
    });

    it('handles zero', () => {
      expect(component.formatVolume(0)).toBe('0 m³');
    });
  });

  describe('result rendering', () => {
    // PostGIS geography reports 38,636 km² for this rectangle; the client's spherical
    // model lands within ~0.3%, so the rendered figure rounds into the 38,5xx-38,6xx range.
    const POLYGON = 'POLYGON((13.6 45.4, 16.6 45.4, 16.6 46.9, 13.6 46.9, 13.6 45.4))';
    const result = {
      scans: [{ scanTime: '2024-06-15T12:00:00Z', mean: 1, min: 0, max: 2, sum: 10, count: 10 }],
      accumulatedRainfallMm: 4.6,
      totalVolumeM3: 372_400_000,
      areaKm2: 38_636,
      scanCount: 1,
      intervalMinutes: 5,
    };

    beforeEach(() => {
      component.fromDatePart = '2024-06-15';
      component.fromTimePart = '00:31';
      component.toDatePart = '2024-06-16';
      component.toTimePart = '00:31';
      component.polygon = POLYGON;
      component.result = result;
      fixture.detectChanges();
    });

    it('states the area and duration once, in a scope banner', () => {
      const scope: HTMLElement = fixture.nativeElement.querySelector('.analysis-scope');
      expect(scope.textContent).toMatch(/38,[56]\d\d/);
      expect(scope.textContent).toContain('km²');
      expect(scope.textContent).toContain('1 day');
    });

    it('derives the displayed area from the polygon, not the server response', () => {
      component.result = { ...result, areaKm2: 999_999 };
      fixture.detectChanges();

      const scope: HTMLElement = fixture.nativeElement.querySelector('.analysis-scope');
      expect(scope.textContent).not.toContain('999,999');
      expect(scope.textContent).toMatch(/38,[56]\d\d/);
    });

    it('places the scope banner above the analyze button and the metrics', () => {
      const el = fixture.nativeElement;
      const scope: HTMLElement = el.querySelector('.analysis-scope');
      const button: HTMLElement = el.querySelector('.analyze-btn');
      const depth: HTMLElement = el.querySelector('.accumulated');

      const follows = (a: Node, b: Node) =>
        !!(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING);
      expect(follows(scope, button)).toBeTrue();
      expect(follows(button, depth)).toBeTrue();
    });

    it('leaves the individual metric labels unqualified', () => {
      const label: HTMLElement = fixture.nativeElement.querySelector('.accumulated .label');
      expect(label.textContent!.trim()).toBe('Accumulated Rainfall');
      expect(label.textContent).not.toContain('km²');
    });

    it('keeps the scope banner up with no result, so the area is known before analyzing', () => {
      component.result = null;
      fixture.detectChanges();

      const scope: HTMLElement = fixture.nativeElement.querySelector('.analysis-scope');
      expect(scope).not.toBeNull();
      expect(scope.textContent).toMatch(/38,[56]\d\d/);
      expect(fixture.nativeElement.querySelector('.accumulated')).toBeNull();
    });

    it('hides the scope banner when no polygon is drawn', () => {
      component.polygon = null;
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector('.analysis-scope')).toBeNull();
    });

    it('drops results that no longer describe the drawn shape', () => {
      component.polygon = 'POLYGON((10 40, 11 40, 11 41, 10 41, 10 40))';
      fixture.detectChanges();

      expect(component.result).toBeNull();
      expect(fixture.nativeElement.querySelector('.accumulated')).toBeNull();
      expect(fixture.nativeElement.querySelector('.analysis-scope')).not.toBeNull();
    });

    it('drops results that no longer describe the selected period', () => {
      component.toDatePart = '2024-06-20';
      component.onRangeChanged();
      fixture.detectChanges();

      expect(component.result).toBeNull();
      expect(fixture.nativeElement.querySelector('.accumulated')).toBeNull();
    });

    it('ignores a repeated emission of the same polygon', () => {
      component.polygon = POLYGON;

      expect(component.result).toBe(result);
    });

    it('shows the collected volume alongside the depth', () => {
      const volume: HTMLElement = fixture.nativeElement.querySelector('.collected .value');
      expect(volume.textContent).toContain('372.4 million');
    });
  });

  it('loadRadar emits scanTimesLoaded', () => {
    const spy = spyOn(component.scanTimesLoaded, 'emit');
    component.fromDatePart = '2024-06-15';
    component.fromTimePart = '12:00';
    component.toDatePart = '2024-06-15';
    component.toTimePart = '14:00';

    component.loadRadar();

    const req = httpTesting.expectOne((r) => r.url === '/api/rainfall/scans');
    req.flush({ scanTimes: ['2024-06-15T12:00:00Z', '2024-06-15T12:05:00Z'] });

    expect(spy).toHaveBeenCalledWith(['2024-06-15T12:00:00Z', '2024-06-15T12:05:00Z']);
    expect(component.loadingScans).toBeFalse();
  });
});
