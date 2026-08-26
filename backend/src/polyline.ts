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
 * Downsample route points so the Overpass query stays small. We only need enough
 * points that consecutive `around` circles (radius = detour) overlap and cover
 * the corridor. Keep a point roughly every `stepMeters`.
 */
export function downsample(points: LatLng[], stepMeters: number): LatLng[] {
  if (points.length <= 2) return points;
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
