import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { QueryPanelComponent } from './query-panel.component';
import { BucketStats, RainfallQueryResponse } from '../../models/rainfall.models';

describe('QueryPanelComponent', () => {
  let component: QueryPanelComponent;
  let fixture: ComponentFixture<QueryPanelComponent>;
  let httpTesting: HttpTestingController;

  const query = <T extends HTMLElement>(selector: string): T =>
    fixture.nativeElement.querySelector(selector);

  const emptyFrames = { frames: [], count: 0, bucketMinutes: 5, scaleMaxMm: null };

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

  describe('default state', () => {
    it('opens on the relative tab with a two hour window', () => {
      expect(component.activeTab).toBe('last');
      expect(component.lastAmount).toBe(2);
      expect(component.lastUnit).toBe('hours');
    });

    it('starts with bucketing off, at one scan per frame', () => {
      expect(component.bucketAmount).toBe(5);
      expect(component.bucketUnit).toBe('minutes');
      expect(component.bucketMinutes).toBe(5);
      expect(component.bucketingEnabled).toBeFalse();
      expect(component.bucketError).toBeNull();
    });

    it('marks the relative tab selected and shows its body', () => {
      const tabs = fixture.nativeElement.querySelectorAll('[role="tab"]');
      expect(tabs.length).toBe(2);
      expect(tabs[0].getAttribute('aria-selected')).toBe('true');
      expect(tabs[1].getAttribute('aria-selected')).toBe('false');
      expect(query('.tab-panel-last')).not.toBeNull();
      expect(query('.tab-panel-range')).toBeNull();
    });

    it('hides the scale selector while frames are per scan', () => {
      expect(query('.scale-select')).toBeNull();
    });

    it('keeps the bucket control outside the tab bodies, since it applies to both', () => {
      const bucketControl = query('.bucket-control');
      expect(bucketControl).not.toBeNull();
      expect(bucketControl.closest('.tab-panel-last')).toBeNull();
    });
  });

  describe('tabs', () => {
    it('reveals the from/to rows when the range tab is selected', () => {
      component.selectTab('range');
      fixture.detectChanges();

      expect(query('.tab-panel-range')).not.toBeNull();
      expect(query('.tab-panel-last')).toBeNull();
      const tabs = fixture.nativeElement.querySelectorAll('[role="tab"]');
      expect(tabs[1].getAttribute('aria-selected')).toBe('true');
    });

    it('prefills from/to with the window the relative tab just resolved', () => {
      component.lastAmount = 90;
      component.lastUnit = 'minutes';

      component.selectTab('range');

      const from = new Date(`${component.fromDatePart}T${component.fromTimePart}`).getTime();
      const to = new Date(`${component.toDatePart}T${component.toTimePart}`).getTime();
      expect(to - from).toBe(90 * 60 * 1000);
      expect(Math.abs(to - Date.now())).toBeLessThan(60_000);
    });

    it('keeps the relative setting when switching back', () => {
      component.lastAmount = 6;
      component.lastUnit = 'days';

      component.selectTab('range');
      component.selectTab('last');

      expect(component.lastAmount).toBe(6);
      expect(component.lastUnit).toBe('days');
      expect(component.selectedDurationLabel).toBe('6 days');
    });

    it('discards a result that described the other tab window', () => {
      component.result = resultWith([]);

      component.selectTab('range');

      expect(component.result).toBeNull();
    });

    it('moves between tabs with the arrow keys', () => {
      const tablist: HTMLElement = query('[role="tablist"]');
      tablist.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
      fixture.detectChanges();

      expect(component.activeTab).toBe('range');

      tablist.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
      fixture.detectChanges();

      expect(component.activeTab).toBe('last');
    });
  });

  describe('bucket size', () => {
    it('converts the selected unit to minutes for the API', () => {
      component.bucketAmount = 15;
      component.bucketUnit = 'minutes';
      expect(component.bucketMinutes).toBe(15);

      component.bucketAmount = 6;
      component.bucketUnit = 'hours';
      expect(component.bucketMinutes).toBe(360);

      component.bucketAmount = 2;
      component.bucketUnit = 'days';
      expect(component.bucketMinutes).toBe(2880);
    });

    it('treats a five minute bucket as bucketing off', () => {
      component.bucketAmount = 5;
      component.bucketUnit = 'minutes';
      expect(component.bucketingEnabled).toBeFalse();

      component.bucketAmount = 10;
      expect(component.bucketingEnabled).toBeTrue();
    });

    it('discards a result that described a different bucket size', () => {
      component.result = resultWith([]);

      component.bucketAmount = 30;
      component.onBucketChanged();

      expect(component.result).toBeNull();
    });

    it('steps by the scan interval in minutes, but by whole hours and days', () => {
      component.bucketUnit = 'minutes';
      expect(component.bucketStep).toBe(5);

      component.bucketUnit = 'hours';
      expect(component.bucketStep).toBe(1);

      component.bucketUnit = 'days';
      expect(component.bucketStep).toBe(1);
    });

    it('applies the step to the bucket input so the spinner matches the unit', () => {
      const input = (): HTMLInputElement =>
        fixture.nativeElement.querySelector('#bucket-amount');

      fixture.detectChanges();
      expect(input().step).toBe('5');
      expect(input().min).toBe('5');

      component.bucketUnit = 'hours';
      fixture.detectChanges();
      expect(input().step).toBe('1');
      expect(input().min).toBe('1');
    });

    it('accepts any whole number of hours, since an hour is already a scan multiple', () => {
      // Wide enough that the bucket-longer-than-the-window rule cannot fire instead.
      component.lastAmount = 7;
      component.lastUnit = 'days';

      component.bucketUnit = 'hours';
      for (const amount of [1, 2, 3, 7]) {
        component.bucketAmount = amount;
        expect(component.bucketError)
          .withContext(`${amount} hours`)
          .toBeNull();
      }
    });
  });

  describe('bucket validation', () => {
    const POLYGON = 'POLYGON((14.5 46.0,14.6 46.0,14.6 46.1,14.5 46.1,14.5 46.0))';

    const setBucket = (amount: number, unit: 'minutes' | 'hours' | 'days') => {
      component.polygon = POLYGON;
      component.bucketAmount = amount;
      component.bucketUnit = unit;
      fixture.detectChanges();
    };

    const buttonsDisabled = () => ({
      loadRadar: query<HTMLButtonElement>('.load-radar-btn').disabled,
      analyze: query<HTMLButtonElement>('.analyze-btn').disabled,
    });

    it('rejects a bucket shorter than one scan', () => {
      setBucket(3, 'minutes');

      expect(component.bucketError).toContain('at least 5 minutes');
      expect(buttonsDisabled()).toEqual({ loadRadar: true, analyze: true });
      expect(query('.bucket-error').textContent).toContain('at least 5 minutes');
    });

    it('rejects a bucket that is not a whole number of scans', () => {
      setBucket(7, 'minutes');

      expect(component.bucketError).toContain('multiple of 5');
      expect(buttonsDisabled()).toEqual({ loadRadar: true, analyze: true });
    });

    it('rejects a bucket longer than the selected window', () => {
      // The relative tab defaults to a two hour window.
      setBucket(4, 'hours');

      expect(component.bucketError).toContain('selected period');
      expect(buttonsDisabled()).toEqual({ loadRadar: true, analyze: true });
    });

    it('accepts a bucket that exactly fills the window', () => {
      setBucket(2, 'hours');

      expect(component.bucketError).toBeNull();
      expect(buttonsDisabled()).toEqual({ loadRadar: false, analyze: false });
    });

    it('re-checks the bucket against the range tab window', () => {
      component.selectTab('range');
      component.fromDatePart = '2024-06-15';
      component.fromTimePart = '12:00';
      component.toDatePart = '2024-06-15';
      component.toTimePart = '13:00';
      setBucket(2, 'hours');

      expect(component.bucketError).toContain('selected period');
    });

    it('refuses to fire a request while the bucket is invalid', () => {
      setBucket(7, 'minutes');

      component.loadRadar();
      component.analyze();

      expect(httpTesting.match(() => true).length).toBe(0);
    });

    it('shows no bucket message while the bucket is valid', () => {
      setBucket(30, 'minutes');

      expect(query('.bucket-error')).toBeNull();
    });
  });

  // An unusable window would otherwise reach toISOString() as an Invalid Date and throw.
  describe('window validation', () => {
    it('blocks a blank relative window', () => {
      component.polygon = 'POLYGON((14.5 46.0,14.6 46.0,14.6 46.1,14.5 46.1,14.5 46.0))';
      component.lastAmount = 0;
      fixture.detectChanges();

      expect(component.windowError).toBe('Enter how far back to look.');
      expect(query<HTMLButtonElement>('.load-radar-btn').disabled).toBeTrue();
      expect(query<HTMLButtonElement>('.analyze-btn').disabled).toBeTrue();
    });

    it('blocks a reversed from/to window', () => {
      component.selectTab('range');
      component.fromDatePart = '2024-06-15';
      component.fromTimePart = '14:00';
      component.toDatePart = '2024-06-15';
      component.toTimePart = '12:00';
      fixture.detectChanges();

      expect(component.windowError).toBe('Select a period that ends after it starts.');
      expect(query('.window-error')).not.toBeNull();

      component.loadRadar();
      expect(httpTesting.match(() => true).length).toBe(0);
    });

    it('does not blame the bucket for an unusable window', () => {
      component.selectTab('range');
      component.fromDatePart = '2024-06-15';
      component.fromTimePart = '14:00';
      component.toDatePart = '2024-06-15';
      component.toTimePart = '12:00';
      component.bucketAmount = 30;

      expect(component.bucketError).toBeNull();
    });
  });

  describe('scale selector', () => {
    it('appears once bucketing is on', () => {
      component.bucketAmount = 60;
      component.bucketUnit = 'minutes';
      fixture.detectChanges();

      expect(query('.scale-select')).not.toBeNull();
    });

    it('pins per-scan frames to the rate scale regardless of the stored choice', () => {
      component.scaleMode = 'autofit';
      component.bucketAmount = 5;
      expect(component.effectiveScale).toBe('rate');

      component.bucketAmount = 60;
      expect(component.effectiveScale).toBe('autofit');
    });

    it('offers the three contract scales', () => {
      component.bucketAmount = 60;
      fixture.detectChanges();

      const select = query<HTMLSelectElement>('.scale-select');
      expect(Array.from(select.options).map((o) => o.value)).toEqual([
        'fixed',
        'autofit',
        'rate',
      ]);
    });
  });

  describe('duration label', () => {
    const setRange = (fromDate: string, fromTime: string, toDate: string, toTime: string) => {
      component.activeTab = 'range';
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

    it('describes the relative tab window without touching the from/to fields', () => {
      component.activeTab = 'last';
      component.lastAmount = 36;
      component.lastUnit = 'hours';
      expect(component.selectedDurationLabel).toBe('36 hours');
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

  describe('bucket period formatting', () => {
    const period = (start: Date, end: Date) =>
      component.formatBucketPeriod(bucket({ start, end }));

    it('shows only the clock time for a per-scan frame', () => {
      expect(period(new Date(2024, 5, 15, 14, 5), new Date(2024, 5, 15, 14, 10))).toBe('14:05');
    });

    it('shows the span for a bucket inside one day', () => {
      expect(period(new Date(2024, 5, 15, 8, 0), new Date(2024, 5, 15, 14, 0))).toBe(
        '08:00–14:00 15/06'
      );
    });

    it('dates both ends for a day-length bucket', () => {
      expect(period(new Date(2024, 5, 15, 6, 0), new Date(2024, 5, 16, 6, 0))).toBe(
        '15/06 06:00 – 16/06 06:00'
      );
    });
  });

  describe('result rendering', () => {
    // PostGIS geography reports 38,636 km² for this rectangle; the client's spherical
    // model lands within ~0.3%, so the rendered figure rounds into the 38,5xx-38,6xx range.
    const POLYGON = 'POLYGON((13.6 45.4, 16.6 45.4, 16.6 46.9, 13.6 46.9, 13.6 45.4))';
    const result = resultWith([
      bucket({
        start: new Date(2024, 5, 15, 12, 0),
        end: new Date(2024, 5, 15, 12, 5),
        depthMm: 0.083,
        meanRateMmH: 1,
        maxRateMmH: 2,
      }),
    ]);

    beforeEach(() => {
      component.activeTab = 'range';
      component.fromDatePart = '2024-06-15';
      component.fromTimePart = '00:31';
      component.toDatePart = '2024-06-16';
      component.toTimePart = '00:31';
      component.polygon = POLYGON;
      component.result = result;
      fixture.detectChanges();
    });

    it('states the area and duration once, in a scope banner', () => {
      const scope = query('.analysis-scope');
      expect(scope.textContent).toMatch(/38,[56]\d\d/);
      expect(scope.textContent).toContain('km²');
      expect(scope.textContent).toContain('1 day');
    });

    it('leaves the bucketing clause out of the banner when frames are per scan', () => {
      expect(query('.analysis-scope').textContent).not.toContain('bucket');
    });

    it('names the bucketing in the scope banner', () => {
      component.bucketAmount = 6;
      component.bucketUnit = 'hours';
      fixture.detectChanges();

      const scope = query('.analysis-scope');
      expect(scope.textContent).toContain('1 day,');
      expect(scope.textContent).toContain('in 4 buckets of 6 hours');
    });

    it('counts a trailing partial bucket in the banner', () => {
      component.bucketAmount = 10;
      component.bucketUnit = 'hours';
      fixture.detectChanges();

      // 24 h of window split 10 h at a time leaves a 4 h remainder.
      expect(query('.analysis-scope').textContent).toContain('in 3 buckets of 10 hours');
    });

    it('derives the displayed area from the polygon, not the server response', () => {
      component.result = { ...result, areaKm2: 999_999 };
      fixture.detectChanges();

      const scope = query('.analysis-scope');
      expect(scope.textContent).not.toContain('999,999');
      expect(scope.textContent).toMatch(/38,[56]\d\d/);
    });

    it('places the scope banner above the analyze button and the metrics', () => {
      const scope = query('.analysis-scope');
      const button = query('.analyze-btn');
      const depth = query('.accumulated');

      const follows = (a: Node, b: Node) =>
        !!(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING);
      expect(follows(scope, button)).toBeTrue();
      expect(follows(button, depth)).toBeTrue();
    });

    it('leaves the individual metric labels unqualified', () => {
      const label = query('.accumulated .label');
      expect(label.textContent!.trim()).toBe('Accumulated Rainfall');
      expect(label.textContent).not.toContain('km²');
    });

    it('keeps the scope banner up with no result, so the area is known before analyzing', () => {
      component.result = null;
      fixture.detectChanges();

      const scope = query('.analysis-scope');
      expect(scope).not.toBeNull();
      expect(scope.textContent).toMatch(/38,[56]\d\d/);
      expect(query('.accumulated')).toBeNull();
    });

    it('hides the scope banner when no polygon is drawn', () => {
      component.polygon = null;
      fixture.detectChanges();

      expect(query('.analysis-scope')).toBeNull();
    });

    it('drops results that no longer describe the drawn shape', () => {
      component.polygon = 'POLYGON((10 40, 11 40, 11 41, 10 41, 10 40))';
      fixture.detectChanges();

      expect(component.result).toBeNull();
      expect(query('.accumulated')).toBeNull();
      expect(query('.analysis-scope')).not.toBeNull();
    });

    it('drops results that no longer describe the selected period', () => {
      component.toDatePart = '2024-06-20';
      component.onRangeChanged();
      fixture.detectChanges();

      expect(component.result).toBeNull();
      expect(query('.accumulated')).toBeNull();
    });

    it('ignores a repeated emission of the same polygon', () => {
      component.polygon = POLYGON;

      expect(component.result).toBe(result);
    });

    it('shows the collected volume alongside the depth', () => {
      expect(query('.collected .value').textContent).toContain('372.4 million');
    });
  });

  describe('bucket table', () => {
    beforeEach(() => {
      component.polygon = 'POLYGON((14.5 46.0,14.6 46.0,14.6 46.1,14.5 46.1,14.5 46.0))';
    });

    const rows = () => fixture.nativeElement.querySelectorAll('.bucket-table tbody tr');

    it('reports each bucket as period, depth, mean and peak', () => {
      component.result = resultWith([
        bucket({
          start: new Date(2024, 5, 15, 6, 0),
          end: new Date(2024, 5, 15, 12, 0),
          depthMm: 4.2,
          meanRateMmH: 0.7,
          maxRateMmH: 12.4,
        }),
      ]);
      fixture.detectChanges();

      const headers = Array.from(
        fixture.nativeElement.querySelectorAll('.bucket-table th')
      ).map((th) => (th as HTMLElement).textContent!.trim());
      expect(headers).toEqual(['Period', 'Depth (mm)', 'Mean (mm/h)', 'Peak (mm/h)']);

      const cells = Array.from(rows()[0].querySelectorAll('td')).map((td) =>
        (td as HTMLElement).textContent!.trim()
      );
      expect(cells[0]).toContain('06:00–12:00 15/06');
      expect(cells[1]).toBe('4.20');
      expect(cells[2]).toBe('0.70');
      expect(cells[3]).toBe('12.40');
    });

    it('marks a partial bucket so it does not read as a quiet period', () => {
      component.result = resultWith([
        bucket({
          start: new Date(2024, 5, 15, 4, 0),
          end: new Date(2024, 5, 15, 6, 0),
          depthMm: 0.4,
          partial: true,
        }),
        bucket({
          start: new Date(2024, 5, 15, 6, 0),
          end: new Date(2024, 5, 15, 12, 0),
          depthMm: 4.2,
        }),
      ]);
      fixture.detectChanges();

      expect(rows()[0].classList).toContain('partial');
      expect(rows()[0].textContent).toContain('partial');
      expect(rows()[1].classList).not.toContain('partial');
      expect(rows()[1].textContent).not.toContain('partial');
    });

    it('summarises how the window was split', () => {
      component.result = {
        ...resultWith([]),
        bucketCount: 4,
        bucketMinutes: 360,
      };
      fixture.detectChanges();

      expect(query('.meta').textContent).toContain('4 buckets of 6 hours');
    });

    it('calls per-scan rows frames rather than buckets', () => {
      component.result = { ...resultWith([]), bucketCount: 24, bucketMinutes: 5 };
      fixture.detectChanges();

      expect(query('.meta').textContent).toContain('24 frames of 5 minutes');
    });
  });

  describe('loadRadar', () => {
    it('resolves the relative window from now, at click time', () => {
      const before = Date.now();

      component.loadRadar();

      const req = httpTesting.expectOne((r) => r.url === '/api/rainfall/frames');
      const from = Date.parse(req.request.params.get('from')!);
      const to = Date.parse(req.request.params.get('to')!);
      expect(to).toBeGreaterThanOrEqual(before);
      expect(to - from).toBe(2 * 60 * 60 * 1000);
      req.flush(emptyFrames);
    });

    it('emits the frames with the scale they must be painted in', () => {
      const spy = spyOn(component.framesLoaded, 'emit');
      component.bucketAmount = 6;
      component.bucketUnit = 'hours';
      component.lastAmount = 1;
      component.lastUnit = 'days';
      component.scaleMode = 'autofit';

      component.loadRadar();

      const req = httpTesting.expectOne((r) => r.url === '/api/rainfall/frames');
      expect(req.request.params.get('bucketMinutes')).toBe('360');
      expect(req.request.params.get('scale')).toBe('autofit');

      const frames = [
        { start: '2024-06-15T00:00:00Z', end: '2024-06-15T06:00:00Z', partial: false },
      ];
      req.flush({ frames, count: 1, bucketMinutes: 360, scaleMaxMm: 42.7 });

      expect(spy).toHaveBeenCalledWith({ frames, scale: 'autofit', scaleMaxMm: 42.7 });
      expect(component.loadingFrames).toBeFalse();
    });

    it('leaves the bucket and scale params off for per-scan frames', () => {
      component.loadRadar();

      const req = httpTesting.expectOne((r) => r.url === '/api/rainfall/frames');
      expect(req.request.params.has('bucketMinutes')).toBeFalse();
      expect(req.request.params.has('scale')).toBeFalse();
      req.flush(emptyFrames);
    });

    it('reports the rate scale for per-scan frames', () => {
      const spy = spyOn(component.framesLoaded, 'emit');
      component.scaleMode = 'fixed';

      component.loadRadar();

      httpTesting.expectOne((r) => r.url === '/api/rainfall/frames').flush(emptyFrames);

      expect(spy).toHaveBeenCalledWith({ frames: [], scale: 'rate', scaleMaxMm: null });
    });

    it('surfaces the server error message', () => {
      component.loadRadar();

      httpTesting
        .expectOne((r) => r.url === '/api/rainfall/frames')
        .flush({ error: 'bucketMinutes must be a multiple of 5' }, { status: 400, statusText: 'Bad Request' });

      expect(component.error).toBe('bucketMinutes must be a multiple of 5');
      expect(component.loadingFrames).toBeFalse();
    });
  });

  // Bucket edges anchor at the window end and walk backwards, so two entry points resolving
  // their own `now` seconds apart would describe different buckets for one "last 2 hours".
  describe('window snapshot', () => {
    const POLYGON = 'POLYGON((14.5 46.0,14.6 46.0,14.6 46.1,14.5 46.1,14.5 46.0))';
    const CLICK_TIME = new Date('2026-07-31T12:00:37Z');

    beforeEach(() => {
      jasmine.clock().install();
      jasmine.clock().mockDate(CLICK_TIME);
    });

    afterEach(() => {
      jasmine.clock().uninstall();
    });

    const loadedWindow = () => {
      component.loadRadar();
      const req = httpTesting.expectOne((r) => r.url === '/api/rainfall/frames');
      const window = {
        from: req.request.params.get('from')!,
        to: req.request.params.get('to')!,
      };
      req.flush(emptyFrames);
      return window;
    };

    const analyzedWindow = () => {
      component.analyze();
      const req = httpTesting.expectOne('/api/rainfall/query');
      const window = { from: req.request.body.from, to: req.request.body.to };
      req.flush(resultWith([]));
      return window;
    };

    it('describes one window to the timeline and the results table', () => {
      component.polygon = POLYGON;

      const loaded = loadedWindow();
      jasmine.clock().tick(3000);
      const analyzed = analyzedWindow();

      expect(analyzed).toEqual(loaded);
    });

    it('holds the window steady no matter which button is pressed first', () => {
      component.polygon = POLYGON;

      const analyzed = analyzedWindow();
      jasmine.clock().tick(3000);
      const loaded = loadedWindow();

      expect(loaded).toEqual(analyzed);
    });

    it('re-resolves now once the relative window changes meaning', () => {
      const first = loadedWindow();

      jasmine.clock().tick(3000);
      component.lastAmount = 3;
      component.onRangeChanged();
      const second = loadedWindow();

      expect(second.to).not.toEqual(first.to);
      expect(Date.parse(second.to) - Date.parse(second.from)).toBe(3 * 60 * 60 * 1000);
    });

    it('re-resolves now after a trip through the range tab', () => {
      const first = loadedWindow();

      component.selectTab('range');
      jasmine.clock().tick(60_000);
      component.selectTab('last');
      const second = loadedWindow();

      expect(second.to).not.toEqual(first.to);
    });

    it('queries exactly the window the range tab was prefilled with', () => {
      component.selectTab('range');

      // The date/time inputs are minute-resolution, so the displayed fields — not the
      // seconds-precise instant they came from — must define the window that is queried.
      const displayed = {
        from: new Date(`${component.fromDatePart}T${component.fromTimePart}`).toISOString(),
        to: new Date(`${component.toDatePart}T${component.toTimePart}`).toISOString(),
      };

      expect(loadedWindow()).toEqual(displayed);
      expect(displayed.to.endsWith(':00.000Z')).toBeTrue();
    });
  });

  describe('analyze', () => {
    const POLYGON = 'POLYGON((14.5 46.0,14.6 46.0,14.6 46.1,14.5 46.1,14.5 46.0))';

    it('omits bucketMinutes for per-scan frames', () => {
      component.polygon = POLYGON;

      component.analyze();

      const req = httpTesting.expectOne('/api/rainfall/query');
      expect(req.request.body.bucketMinutes).toBeUndefined();
      req.flush(resultWith([]));
    });

    it('sends the bucket size in minutes', () => {
      component.polygon = POLYGON;
      component.lastAmount = 1;
      component.lastUnit = 'days';
      component.bucketAmount = 6;
      component.bucketUnit = 'hours';

      component.analyze();

      const req = httpTesting.expectOne('/api/rainfall/query');
      expect(req.request.body.bucketMinutes).toBe(360);
      req.flush(resultWith([]));
    });
  });
});

function bucket(overrides: Partial<Omit<BucketStats, 'start' | 'end'>> & {
  start: Date;
  end: Date;
}): BucketStats {
  const { start, end, ...rest } = overrides;
  return {
    start: start.toISOString(),
    end: end.toISOString(),
    depthMm: 0,
    meanRateMmH: 0,
    minRateMmH: 0,
    maxRateMmH: 0,
    volumeM3: 0,
    scanCount: 1,
    partial: false,
    ...rest,
  };
}

function resultWith(buckets: BucketStats[]): RainfallQueryResponse {
  return {
    buckets,
    accumulatedRainfallMm: 4.6,
    totalVolumeM3: 372_400_000,
    areaKm2: 38_636,
    bucketCount: buckets.length,
    bucketMinutes: 5,
  };
}
