/** Equatorial radius (m) — the sphere Leaflet's own area helper assumes. */
const EARTH_RADIUS_M = 6378137;

const DEG_TO_RAD = Math.PI / 180;

/** Longitude/latitude pairs of a WKT POLYGON's outer ring; inner rings are ignored (shapes here have none). */
function parseOuterRing(wkt: string): [number, number][] {
  const ring = /\(\(([^)]*)\)/.exec(wkt);
  if (!ring) return [];

  return ring[1]
    .split(',')
    .map((pair) => pair.trim().split(/\s+/).map(Number) as [number, number])
    .filter(([lon, lat]) => Number.isFinite(lon) && Number.isFinite(lat));
}

/**
 * Geodesic area of a WKT POLYGON in km², by spherical excess.
 *
 * Runs ~0.3% under PostGIS's `geography` area, which uses the WGS84 spheroid. The UI
 * derives every displayed area from this one function so the figure never jumps; do not
 * mix it with the server's `areaKm2` in the same view.
 */
export function polygonAreaKm2(wkt: string): number {
  const ring = parseOuterRing(wkt);
  if (ring.length < 3) return 0;

  let area = 0;
  for (let i = 0; i < ring.length; i++) {
    const [lon1, lat1] = ring[i];
    const [lon2, lat2] = ring[(i + 1) % ring.length];
    area +=
      (lon2 - lon1) * DEG_TO_RAD * (2 + Math.sin(lat1 * DEG_TO_RAD) + Math.sin(lat2 * DEG_TO_RAD));
  }

  return Math.abs((area * EARTH_RADIUS_M * EARTH_RADIUS_M) / 2) / 1e6;
}
