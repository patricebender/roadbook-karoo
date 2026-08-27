// Google encoded polyline decoder (precision 5), plus route utilities.
// No dependency needed — the algorithm is small and stable.

export interface LatLng {
  lat: number;
  lng: number;
}

/** Decode a Google encoded polyline (precision 5) to lat/lng points. */
export function decodePolyline(encoded: string, precision = 5): LatLng[] {
  const factor = Math.pow(10, precision);
  const points: LatLng[] = [];
  let index = 0;
  let lat = 0;
  let lng = 0;

  while (index < encoded.length) {
    let result = 0;
    let shift = 0;
    let byte: number;
    do {
      byte = encoded.charCodeAt(index++) - 63;
      result |= (byte & 0x1f) << shift;
      shift += 5;
    } while (byte >= 0x20);
    lat += result & 1 ? ~(result >> 1) : result >> 1;

    result = 0;
    shift = 0;
    do {
      byte = encoded.charCodeAt(index++) - 63;
      result |= (byte & 0x1f) << shift;
      shift += 5;
    } while (byte >= 0x20);
    lng += result & 1 ? ~(result >> 1) : result >> 1;

    points.push({ lat: lat / factor, lng: lng / factor });
  }
  return points;
}

/**
 * Sample the route into `around`-query anchor points that cover the whole
 * corridor with no gaps, while keeping the query bounded.
 *
 * Coverage: each anchor searches a circle of `radius`. Two consecutive circles
 * whose centers are on the route cover the strip between them (out to `radius`
 * either side) as long as the spacing is <= radius*sqrt(3) — the point where the
 * circles stop overlapping over the route line. We use that max spacing so we
 * emit the fewest anchors that still miss no POIs within `radius` of the route.
 *
 * Bound: a very long route would still produce too many anchors for one Overpass
 * query, so we cap at `maxPoints`, widening spacing to fit. (Only extreme routes
 * hit this; then the corridor is sampled slightly sparser but the query stays
 * fast and valid.)
 */
const COVERAGE_SPACING_FACTOR = 1.7; // ~sqrt(3), no-gap coverage

export function sampleCorridor(
  points: LatLng[],
  radiusMeters: number,
  maxPoints = 40,
): LatLng[] {
  if (points.length <= 2) return points;
  const total = routeLength(points);
  // Max no-gap spacing; widen further only if needed to stay under the cap.
  const stepMeters = Math.max(
    radiusMeters * COVERAGE_SPACING_FACTOR,
    total / maxPoints,
  );

  const kept: LatLng[] = [points[0]!];
  let acc = 0;
  for (let i = 1; i < points.length; i++) {
    acc += haversine(points[i - 1]!, points[i]!);
    if (acc >= stepMeters) {
      kept.push(points[i]!);
      acc = 0;
    }
  }
  const last = points[points.length - 1]!;
  if (kept[kept.length - 1] !== last) kept.push(last);
  return kept;
}

/** Total length of a route in meters. */
export function routeLength(points: LatLng[]): number {
  let total = 0;
  for (let i = 1; i < points.length; i++) total += haversine(points[i - 1]!, points[i]!);
  return total;
}

/** Great-circle distance in meters. */
export function haversine(a: LatLng, b: LatLng): number {
  const R = 6371000;
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const lat1 = toRad(a.lat);
  const lat2 = toRad(b.lat);
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}

/**
 * Distance (meters) from a point to the nearest route vertex, and the cumulative
 * route distance at that vertex. Cheap approximation: nearest-vertex, not
 * nearest-segment — good enough to sort POIs by "distance along route".
 */
export function nearestAlongRoute(
  route: LatLng[],
  cumulative: number[],
  p: LatLng,
): { distanceToRoute: number; distanceAlong: number } {
  let best = Infinity;
  let bestAlong = 0;
  for (let i = 0; i < route.length; i++) {
    const d = haversine(route[i]!, p);
    if (d < best) {
      best = d;
      bestAlong = cumulative[i]!;
    }
  }
  return { distanceToRoute: best, distanceAlong: bestAlong };
}

/**
 * Distance (meters) from point `p` to the route polyline, measured against the
 * nearest *segment* (not just vertices), plus the cumulative route distance at
 * the closest point. Exact enough for corridor membership + "distance along".
 *
 * Uses a local equirectangular projection (meters) around `p`; fine at the
 * scale of a detour radius.
 */
export function distanceToRoute(
  route: LatLng[],
  cumulative: number[],
  p: LatLng,
): { distanceToRoute: number; distanceAlong: number } {
  const mPerDegLat = 111_320;
  const mPerDegLng = 111_320 * Math.cos(toRad(p.lat));
  const px = p.lng * mPerDegLng;
  const py = p.lat * mPerDegLat;

  let best = Infinity;
  let bestAlong = 0;
  for (let i = 1; i < route.length; i++) {
    const a = route[i - 1]!;
    const b = route[i]!;
    const ax = a.lng * mPerDegLng, ay = a.lat * mPerDegLat;
    const bx = b.lng * mPerDegLng, by = b.lat * mPerDegLat;
    const dx = bx - ax, dy = by - ay;
    const segLen2 = dx * dx + dy * dy;
    // Projection factor t of p onto segment [a,b], clamped to the segment.
    const t = segLen2 === 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / segLen2));
    const cx = ax + t * dx, cy = ay + t * dy;
    const d = Math.hypot(px - cx, py - cy);
    if (d < best) {
      best = d;
      // cumulative at vertex a + distance from a to the projection point.
      bestAlong = cumulative[i - 1]! + Math.hypot(cx - ax, cy - ay);
    }
  }
  return { distanceToRoute: best, distanceAlong: bestAlong };
}

/** Cumulative distance (meters) at each route vertex. */
export function cumulativeDistances(route: LatLng[]): number[] {
  const out: number[] = [0];
  for (let i = 1; i < route.length; i++) {
    out.push(out[i - 1]! + haversine(route[i - 1]!, route[i]!));
  }
  return out;
}

function toRad(deg: number): number {
  return (deg * Math.PI) / 180;
}
