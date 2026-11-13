import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { AppComponent } from './app.component';
import { Frame } from './models/rainfall.models';

describe('AppComponent', () => {
  let component: AppComponent;

  const FRAME: Frame = {
    start: '2026-03-30T12:00:00Z',
    end: '2026-03-30T12:05:00Z',
    partial: false,
  };

  const BOUNDS_HEADERS = {
    'X-Bounds-South': '45.0',
    'X-Bounds-West': '13.0',
    'X-Bounds-North': '47.0',
    'X-Bounds-East': '16.0',
  };

  const LEGEND_HEADERS = {
    'X-Legend-Unit': 'mm',
    'X-Legend-Thresholds': '0.2,0.5,1',
    'X-Legend-Colors': '085AFE,008CFE,00AEFD,00C8FE',
  };

  const overlayResponse = (headers: Record<string, string>) =>
    new Response(new Blob(['png']), { status: 200, headers });

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const fixture = TestBed.createComponent(AppComponent);
    component = fixture.componentInstance;
  });

  it('should create the app', () => {
    expect(component).toBeTruthy();
  });

  it('should set currentPolygon on polygonDrawn', () => {
    component.onPolygonDrawn('POLYGON((14 46, 15 46, 15 47, 14 47, 14 46))');
    expect(component.currentPolygon).toBe('POLYGON((14 46, 15 46, 15 47, 14 47, 14 46))');
  });

  it('should clear polygon and error on polygonCleared but keep frames', () => {
    component.currentPolygon = 'POLYGON((14 46, 15 46, 15 47, 14 47, 14 46))';
    component.frames = [FRAME];
    component.overlayError = 'some error';

    component.onPolygonCleared();

    expect(component.currentPolygon).toBeNull();
    expect(component.frames).toEqual([FRAME]);
    expect(component.overlayError).toBeNull();
  });

  it('should set overlayLoading during overlay fetch', async () => {
    spyOn(window, 'fetch').and.returnValue(Promise.resolve(overlayResponse(BOUNDS_HEADERS)));

    const promise = component.onFrameSelected(FRAME);
    expect(component.overlayLoading).toBeTrue();

    await promise;
    expect(component.overlayLoading).toBeFalse();
  });

  it('should set overlayError on fetch failure', async () => {
    spyOn(window, 'fetch').and.returnValue(Promise.reject(new Error('Network error')));

    await component.onFrameSelected(FRAME);

    expect(component.overlayError).toBe('Failed to load overlay');
    expect(component.overlayLoading).toBeFalse();
  });

  it('should set overlayError on non-ok response', async () => {
    spyOn(window, 'fetch').and.returnValue(Promise.resolve(new Response(null, { status: 404 })));

    await component.onFrameSelected(FRAME);

    expect(component.overlayError).toBe('Overlay failed (HTTP 404)');
    expect(component.overlayLoading).toBeFalse();
  });

  it('should set overlayError when bounds headers are missing', async () => {
    spyOn(window, 'fetch').and.returnValue(Promise.resolve(overlayResponse({})));

    await component.onFrameSelected(FRAME);

    expect(component.overlayError).toBe('Missing bounds in overlay response');
    expect(component.overlayLoading).toBeFalse();
  });

  it('should revoke previous blob URL when loading new overlay', async () => {
    const revokespy = spyOn(URL, 'revokeObjectURL');

    spyOn(window, 'fetch').and.returnValues(
      Promise.resolve(overlayResponse(BOUNDS_HEADERS)),
      Promise.resolve(overlayResponse(BOUNDS_HEADERS)),
    );

    await component.onFrameSelected(FRAME);
    expect(revokespy).not.toHaveBeenCalled();

    await component.onFrameSelected({ ...FRAME, start: '2026-03-30T12:05:00Z' });
    expect(revokespy).toHaveBeenCalledTimes(1);
  });

  describe('overlay request', () => {
    it('addresses the frame by span and carries the scale the frames were loaded with', () => {
      const fetchSpy = spyOn(window, 'fetch').and.returnValue(
        Promise.resolve(overlayResponse(BOUNDS_HEADERS))
      );

      component.onFramesLoaded({ frames: [FRAME], scale: 'autofit', scaleMaxMm: 42.7 });

      const url = fetchSpy.calls.mostRecent().args[0] as string;
      expect(url).toContain('/api/rainfall/overlay?');
      expect(url).toContain('scale=autofit');
      expect(url).toContain('scaleMax=42.7');
    });

    it('shows the first frame of a newly loaded sequence', () => {
      const fetchSpy = spyOn(window, 'fetch').and.returnValue(
        Promise.resolve(overlayResponse(BOUNDS_HEADERS))
      );
      const second = { ...FRAME, start: '2026-03-30T12:05:00Z' };

      component.onFramesLoaded({ frames: [FRAME, second], scale: 'rate', scaleMaxMm: null });

      expect(component.frames.length).toBe(2);
      expect(fetchSpy.calls.count()).toBe(1);
      expect(fetchSpy.calls.mostRecent().args[0] as string).toContain(
        encodeURIComponent(FRAME.start)
      );
    });
  });

  describe('legend', () => {
    it('takes the legend from the overlay response headers', async () => {
      spyOn(window, 'fetch').and.returnValue(
        Promise.resolve(overlayResponse({ ...BOUNDS_HEADERS, ...LEGEND_HEADERS }))
      );

      await component.onFrameSelected(FRAME);

      expect(component.legend).toEqual({
        unit: 'mm',
        thresholds: [0.2, 0.5, 1],
        colors: ['085AFE', '008CFE', '00AEFD', '00C8FE'],
      });
    });

    it('clears the legend when an overlay reports none', async () => {
      spyOn(window, 'fetch').and.returnValues(
        Promise.resolve(overlayResponse({ ...BOUNDS_HEADERS, ...LEGEND_HEADERS })),
        Promise.resolve(overlayResponse(BOUNDS_HEADERS)),
      );

      await component.onFrameSelected(FRAME);
      expect(component.legend).not.toBeNull();

      await component.onFrameSelected(FRAME);
      expect(component.legend).toBeNull();
    });

    it('drops a stale legend as soon as a new sequence is requested', () => {
      spyOn(window, 'fetch').and.returnValue(
        Promise.resolve(overlayResponse(BOUNDS_HEADERS))
      );
      component.legend = { unit: 'mm/h', thresholds: [1], colors: ['085AFE', '008CFE'] };

      component.onFramesLoaded({ frames: [FRAME], scale: 'fixed', scaleMaxMm: null });

      expect(component.legend).toBeNull();
    });
  });
});
