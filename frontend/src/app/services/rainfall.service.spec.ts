import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { RainfallService } from './rainfall.service';
import { RainfallQueryRequest, RainfallQueryResponse, ScanTimesResponse } from '../models/rainfall.models';

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
      scans: [
        {
          scanTime: '2024-01-01T00:00:00Z',
          mean: 1.5,
          min: 0.0,
          max: 3.0,
          sum: 15.0,
          count: 10,
        },
      ],
      accumulatedRainfallMm: 1.5,
      scanCount: 1,
      intervalMinutes: 5,
    };

    service.queryRainfall(request).subscribe((response) => {
      expect(response).toEqual(mockResponse);
    });

    const req = httpTesting.expectOne('/api/rainfall/query');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(request);
    req.flush(mockResponse);
  });

  it('getScanTimes sends correct query params', () => {
    const from = '2024-01-01T00:00:00Z';
    const to = '2024-01-01T01:00:00Z';

    const mockResponse: ScanTimesResponse = {
      scanTimes: ['2024-01-01T00:00:00Z', '2024-01-01T00:05:00Z'],
      count: 2,
    };

    service.getScanTimes(from, to).subscribe((response) => {
      expect(response).toEqual(mockResponse);
    });

    const req = httpTesting.expectOne(
      (r) => r.url === '/api/rainfall/scans' && r.params.get('from') === from && r.params.get('to') === to
    );
    expect(req.request.method).toBe('GET');
    req.flush(mockResponse);
  });

  it('getOverlayUrl returns encoded URL', () => {
    const timestamp = '2024-01-01T00:00:00+01:00';
    const url = service.getOverlayUrl(timestamp);
    expect(url).toBe(`/api/rainfall/overlay/${encodeURIComponent(timestamp)}`);
    expect(url).toBe('/api/rainfall/overlay/2024-01-01T00%3A00%3A00%2B01%3A00');
  });
});
