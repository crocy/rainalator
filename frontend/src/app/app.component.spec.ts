import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { AppComponent } from './app.component';

describe('AppComponent', () => {
  let component: AppComponent;

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

  it('should clear polygon and error on polygonCleared but keep scanTimes', () => {
    component.currentPolygon = 'POLYGON((14 46, 15 46, 15 47, 14 47, 14 46))';
    component.scanTimes = ['2026-03-30T12:00:00Z'];
    component.overlayError = 'some error';

    component.onPolygonCleared();

    expect(component.currentPolygon).toBeNull();
    expect(component.scanTimes).toEqual(['2026-03-30T12:00:00Z']);
    expect(component.overlayError).toBeNull();
  });

  it('should set overlayLoading during overlay fetch', async () => {
    const mockResponse = new Response(new Blob(['png']), {
      status: 200,
      headers: {
        'X-Bounds-South': '45.0',
        'X-Bounds-West': '13.0',
        'X-Bounds-North': '47.0',
        'X-Bounds-East': '16.0',
      },
    });
    spyOn(window, 'fetch').and.returnValue(Promise.resolve(mockResponse));

    const promise = component.onScanSelected('2026-03-30T12:00:00Z');
    expect(component.overlayLoading).toBeTrue();

    await promise;
    expect(component.overlayLoading).toBeFalse();
  });

  it('should set overlayError on fetch failure', async () => {
    spyOn(window, 'fetch').and.returnValue(Promise.reject(new Error('Network error')));

    await component.onScanSelected('2026-03-30T12:00:00Z');

    expect(component.overlayError).toBe('Failed to load overlay');
    expect(component.overlayLoading).toBeFalse();
  });

  it('should set overlayError on non-ok response', async () => {
    const mockResponse = new Response(null, { status: 404 });
    spyOn(window, 'fetch').and.returnValue(Promise.resolve(mockResponse));

    await component.onScanSelected('2026-03-30T12:00:00Z');

    expect(component.overlayError).toBe('Overlay failed (HTTP 404)');
    expect(component.overlayLoading).toBeFalse();
  });

  it('should set overlayError when bounds headers are missing', async () => {
    const mockResponse = new Response(new Blob(['png']), { status: 200 });
    spyOn(window, 'fetch').and.returnValue(Promise.resolve(mockResponse));

    await component.onScanSelected('2026-03-30T12:00:00Z');

    expect(component.overlayError).toBe('Missing bounds in overlay response');
    expect(component.overlayLoading).toBeFalse();
  });

  it('should revoke previous blob URL when loading new overlay', async () => {
    const revokespy = spyOn(URL, 'revokeObjectURL');

    const makeResponse = () => new Response(new Blob(['png']), {
      status: 200,
      headers: {
        'X-Bounds-South': '45.0',
        'X-Bounds-West': '13.0',
        'X-Bounds-North': '47.0',
        'X-Bounds-East': '16.0',
      },
    });

    spyOn(window, 'fetch').and.returnValues(
      Promise.resolve(makeResponse()),
      Promise.resolve(makeResponse()),
    );

    await component.onScanSelected('2026-03-30T12:00:00Z');
    expect(revokespy).not.toHaveBeenCalled();

    await component.onScanSelected('2026-03-30T12:05:00Z');
    expect(revokespy).toHaveBeenCalledTimes(1);
  });
});
