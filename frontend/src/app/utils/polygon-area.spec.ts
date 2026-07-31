import { polygonAreaKm2 } from './polygon-area';

describe('polygonAreaKm2', () => {
  const rect = (lon0: number, lat0: number, lon1: number, lat1: number) =>
    `POLYGON((${lon0} ${lat0}, ${lon1} ${lat0}, ${lon1} ${lat1}, ${lon0} ${lat1}, ${lon0} ${lat0}))`;

  it('matches the value PostGIS geography reports for the same shape', () => {
    // Cross-checked against SELECT ST_Area(...::geography)/1e6 = 38636 km².
    // The spherical model here runs ~0.3% off the WGS84 spheroid, hence the tolerance.
    const area = polygonAreaKm2(rect(13.6, 45.4, 16.6, 46.9));

    expect(area).toBeCloseTo(38636, -2.4);
    expect(Math.abs(area - 38636) / 38636).toBeLessThan(0.005);
  });

  it('scales linearly with longitude span', () => {
    const single = polygonAreaKm2(rect(14.0, 45.5, 15.0, 46.0));
    const double = polygonAreaKm2(rect(14.0, 45.5, 16.0, 46.0));

    expect(double / single).toBeCloseTo(2, 3);
  });

  it('shrinks with latitude for a fixed degree span', () => {
    const equatorial = polygonAreaKm2(rect(14.0, 0.0, 15.0, 1.0));
    const temperate = polygonAreaKm2(rect(14.0, 45.0, 15.0, 46.0));

    expect(temperate).toBeLessThan(equatorial);
  });

  it('is unaffected by winding order', () => {
    const clockwise = 'POLYGON((14 45, 14 46, 15 46, 15 45, 14 45))';
    const counterClockwise = 'POLYGON((14 45, 15 45, 15 46, 14 46, 14 45))';

    expect(polygonAreaKm2(clockwise)).toBeCloseTo(polygonAreaKm2(counterClockwise), 6);
  });

  it('handles the many-vertex rings produced by circle drawing', () => {
    const points: string[] = [];
    for (let i = 0; i <= 64; i++) {
      const angle = (i * 2 * Math.PI) / 64;
      points.push(`${14.5 + 0.1 * Math.cos(angle)} ${46 + 0.1 * Math.sin(angle)}`);
    }

    const area = polygonAreaKm2(`POLYGON((${points.join(', ')}))`);

    // Ellipse in degrees -> roughly pi * (0.1deg lon) * (0.1deg lat) in km
    expect(area).toBeGreaterThan(200);
    expect(area).toBeLessThan(300);
  });

  it('returns zero for degenerate or unparseable input', () => {
    expect(polygonAreaKm2('')).toBe(0);
    expect(polygonAreaKm2('not a polygon')).toBe(0);
    expect(polygonAreaKm2('POLYGON((14 45, 15 45, 14 45))')).toBe(0);
  });
});
