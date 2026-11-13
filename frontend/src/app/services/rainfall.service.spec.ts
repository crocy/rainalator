import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { RainfallService } from './rainfall.service';
import {
  FramesResponse,
  RainfallQueryRequest,
  RainfallQueryResponse,
} from '../models/rainfall.models';

describe('RainfallService', () => {
  let service: RainfallService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(RainfallService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('queryRainfall posts to correct endpoint', () => {
    const request: RainfallQueryRequest = {
      polygon: 'POLYGON((14.5 46.0,14.6 46.0,14.6 46.1,14.5 46.1,14.5 46.0))',
      from: '2024-01-01T00:00:00Z',
      to: '2024-01-01T01:00:00Z',
    };

    const mockResponse: RainfallQueryResponse = {
      buckets: [
        {
          start: '2024-01-01T00:00:00Z',
          end: '2024-01-01T00:05:00Z',
          depthMm: 0.125,
          meanRateMmH: 1.5,
          minRateMmH: 0.0,
          maxRateMmH: 3.0,
          volumeM3: 1_250,
          scanCount: 1,
          partial: false,
        },
      ],
      accumulatedRainfallMm: 1.5,
      totalVolumeM3: 15_000,
      areaKm2: 10,
      bucketCount: 1,
      bucketMinutes: 5,
    };

    service.queryRainfall(request).subscribe((response) => {
      expect(response).toEqual(mockResponse);
    });

    const req = httpTesting.expectOne('/api/rainfall/query');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(request);
    req.flush(mockResponse);
  });

  it('queryRainfall carries bucketMinutes when the caller buckets', () => {
    const request: RainfallQueryRequest = {
      polygon: 'POLYGON((14.5 46.0,14.6 46.0,14.6 46.1,14.5 46.1,14.5 46.0))',
      from: '2024-01-01T00:00:00Z',
      to: '2024-01-02T00:00:00Z',
      bucketMinutes: 360,
    };

    service.queryRainfall(request).subscribe();

    const req = httpTesting.expectOne('/api/rainfall/query');
    expect(req.request.body.bucketMinutes).toBe(360);
    req.flush({
      buckets: [],
      accumulatedRainfallMm: 0,
      totalVolumeM3: 0,
      areaKm2: 0,
      bucketCount: 0,
      bucketMinutes: 360,
    });
  });

  it('getFrames sends only from and to for per-scan frames', () => {
    const from = '2024-01-01T00:00:00Z';
    const to = '2024-01-01T01:00:00Z';

    const mockResponse: FramesResponse = {
      frames: [
        { start: '2024-01-01T00:00:00Z', end: '2024-01-01T00:05:00Z', partial: false },
        { start: '2024-01-01T00:05:00Z', end: '2024-01-01T00:10:00Z', partial: false },
      ],
      count: 2,
      bucketMinutes: 5,
      scaleMaxMm: null,
    };

    service.getFrames(from, to).subscribe((response) => {
      expect(response).toEqual(mockResponse);
    });

    const req = httpTesting.expectOne((r) => r.url === '/api/rainfall/frames');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('from')).toBe(from);
    expect(req.request.params.get('to')).toBe(to);
    expect(req.request.params.has('bucketMinutes')).toBeFalse();
    expect(req.request.params.has('scale')).toBeFalse();
    req.flush(mockResponse);
  });

  it('getFrames sends the bucket size and scale when bucketing', () => {
    service.getFrames('2024-01-01T00:00:00Z', '2024-01-02T00:00:00Z', 360, 'autofit').subscribe();

    const req = httpTesting.expectOne((r) => r.url === '/api/rainfall/frames');
    expect(req.request.params.get('bucketMinutes')).toBe('360');
    expect(req.request.params.get('scale')).toBe('autofit');
    req.flush({ frames: [], count: 0, bucketMinutes: 360, scaleMaxMm: 42.7 });
  });

  describe('getOverlayUrl', () => {
    const frame = {
      start: '2024-01-01T00:00:00Z',
      end: '2024-01-01T00:05:00Z',
      partial: false,
    };

    it('addresses the bucket by its start and end', () => {
      const url = service.getOverlayUrl(frame, 'rate', null);
      expect(url).toBe(
        '/api/rainfall/overlay?start=2024-01-01T00%3A00%3A00Z&end=2024-01-01T00%3A05%3A00Z&scale=rate'
      );
    });

    it('encodes a zone offset in the timestamps', () => {
      const url = service.getOverlayUrl(
        { start: '2024-01-01T00:00:00+01:00', end: '2024-01-01T00:05:00+01:00', partial: false },
        'fixed',
        null
      );
      expect(url).toContain('start=2024-01-01T00%3A00%3A00%2B01%3A00');
    });

    it('carries scaleMax for autofit', () => {
      const url = service.getOverlayUrl(frame, 'autofit', 42.7);
      expect(url).toContain('scale=autofit');
      expect(url).toContain('scaleMax=42.7');
    });

    it('omits scaleMax for the scales that do not take one', () => {
      expect(service.getOverlayUrl(frame, 'fixed', 42.7)).not.toContain('scaleMax');
      expect(service.getOverlayUrl(frame, 'rate', 42.7)).not.toContain('scaleMax');
    });
  });

  describe('parseLegend', () => {
    it('reads the unit, thresholds and colours from the overlay headers', () => {
      const legend = service.parseLegend(
        new Headers({
          'X-Legend-Unit': 'mm',
          'X-Legend-Thresholds': '0.2,0.5,1',
          'X-Legend-Colors': '085AFE,008CFE,00AEFD,00C8FE',
        })
      );

      expect(legend).toEqual({
        unit: 'mm',
        thresholds: [0.2, 0.5, 1],
        colors: ['085AFE', '008CFE', '00AEFD', '00C8FE'],
      });
    });

    it('returns null when a legend header is absent', () => {
      const legend = service.parseLegend(
        new Headers({
          'X-Legend-Thresholds': '0.2,0.5,1',
          'X-Legend-Colors': '085AFE,008CFE,00AEFD,00C8FE',
        })
      );

      expect(legend).toBeNull();
    });

    it('returns null when the bands and thresholds describe different pictures', () => {
      const legend = service.parseLegend(
        new Headers({
          'X-Legend-Unit': 'mm',
          'X-Legend-Thresholds': '0.2,0.5,1',
          'X-Legend-Colors': '085AFE,008CFE',
        })
      );

      expect(legend).toBeNull();
    });

    it('returns null when a threshold is not a number', () => {
      const legend = service.parseLegend(
        new Headers({
          'X-Legend-Unit': 'mm',
          'X-Legend-Thresholds': '0.2,oops,1',
          'X-Legend-Colors': '085AFE,008CFE,00AEFD,00C8FE',
        })
      );

      expect(legend).toBeNull();
    });
  });
});
