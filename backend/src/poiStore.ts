// Spatial POI store backed by the pre-extracted SQLite database (pois.sqlite).
// Replaces the Overpass client: queries run in-process against an R*Tree index,
// so a route corridor is one spatial lookup (ms) instead of many `around` calls.

import Database from "better-sqlite3";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import type { Category, Poi, PoiType } from "./contract.js";
import {
  type LatLng,
  cumulativeDistances,
  distanceToRoute,
  haversine,
} from "./polyline.js";

const DB_PATH =
  process.env.POI_DB_PATH ??
  resolve(dirname(fileURLToPath(import.meta.url)), "..", "pois.sqlite");

interface PoiRow {
  osm_id: string;
  lat: number;
  lng: number;
  type: string;
  category: string;
  name: string | null;
}

let db: Database.Database | null = null;
function getDb(): Database.Database {
  if (!db) {
    db = new Database(DB_PATH, { readonly: true, fileMustExist: true });
  }
  return db;
}

/** Degrees of latitude per meter (constant); longitude scaled by cos(lat). */
const M_PER_DEG_LAT = 111_320;

function rowToPoi(r: PoiRow, distancesAlongRoute: number[]): Poi {
  return {
    id: `osm:${r.osm_id}`,
    lat: r.lat,
    lng: r.lng,
    type: r.type as PoiType,
    name: r.name,
    distancesAlongRoute,
    tags: {},
  };
}

/** R*Tree range-scan candidates within a lat/lng bounding box, filtered by category. */
function candidatesInBox(
  minLat: number,
  maxLat: number,
  minLng: number,
  maxLng: number,
  categories: Category[],
): PoiRow[] {
  const placeholders = categories.map(() => "?").join(",");
  const stmt = getDb().prepare(`
    SELECT p.osm_id, p.lat, p.lng, p.type, p.category, p.name
    FROM poi_rtree r
    JOIN poi p ON p.id = r.id
    WHERE r.maxLat >= ? AND r.minLat <= ?
      AND r.maxLng >= ? AND r.minLng <= ?
      AND p.category IN (${placeholders})
  `);
  return stmt.all(minLat, maxLat, minLng, maxLng, ...categories) as PoiRow[];
}

/** Build roadbook POIs along a route corridor (radius either side of the line). */
export function buildAlongRoute(
  route: LatLng[],
  radiusMeters: number,
  categories: Category[],
): Poi[] {
  if (route.length < 2 || categories.length === 0) return [];

  // Bounding box of the whole route, expanded by the radius.
  let minLat = Infinity, maxLat = -Infinity, minLng = Infinity, maxLng = -Infinity;
  for (const p of route) {
    minLat = Math.min(minLat, p.lat); maxLat = Math.max(maxLat, p.lat);
    minLng = Math.min(minLng, p.lng); maxLng = Math.max(maxLng, p.lng);
  }
  const dLat = radiusMeters / M_PER_DEG_LAT;
  const midLat = (minLat + maxLat) / 2;
  const dLng = radiusMeters / (M_PER_DEG_LAT * Math.cos((midLat * Math.PI) / 180));

  const rows = candidatesInBox(
    minLat - dLat, maxLat + dLat, minLng - dLng, maxLng + dLng, categories,
  );

  // Exact corridor refine: keep POIs within radius of the route *line*.
  const cumulative = cumulativeDistances(route);
  const pois: Poi[] = [];
  for (const r of rows) {
    const { distanceToRoute: d, distanceAlong } = distanceToRoute(
      route, cumulative, { lat: r.lat, lng: r.lng },
    );
    if (d <= radiusMeters) {
      pois.push(rowToPoi(r, [Math.round(distanceAlong)]));
    }
  }
  pois.sort((a, b) => (a.distancesAlongRoute[0] ?? 0) - (b.distancesAlongRoute[0] ?? 0));
  return pois;
}

/** Build POIs near a single point (fallback when no route is loaded). */
export function buildNearby(
  center: LatLng,
  radiusMeters: number,
  categories: Category[],
): Poi[] {
  if (categories.length === 0) return [];
  const dLat = radiusMeters / M_PER_DEG_LAT;
  const dLng = radiusMeters / (M_PER_DEG_LAT * Math.cos((center.lat * Math.PI) / 180));

  const rows = candidatesInBox(
    center.lat - dLat, center.lat + dLat, center.lng - dLng, center.lng + dLng, categories,
  );
  return rows
    .filter((r) => haversine(center, { lat: r.lat, lng: r.lng }) <= radiusMeters)
    .map((r) => rowToPoi(r, []));
}
