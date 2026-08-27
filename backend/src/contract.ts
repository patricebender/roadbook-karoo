// Shared POI contract between backend and the Kotlin extension.
// Keep in sync with the Kotlin data classes. `type` values must be a subset of
// karoo-ext Symbol.POI.Types so the extension can render them directly as map pins.

/** POI category toggles the rider switches on/off when building. */
export type Category = "food_drink" | "water_restroom" | "bike" | "fuel";

export const ALL_CATEGORIES: Category[] = [
  "food_drink",
  "water_restroom",
  "bike",
  "fuel",
];

/** Subset of karoo-ext Symbol.POI.Types we emit for the MVP. */
export type PoiType =
  | "COFFEE"
  | "FOOD"
  | "BAR"
  | "CONVENIENCE_STORE"
  | "REST_STOP" // drinking water
  | "RESTROOM"
  | "BIKE_SHOP"
  | "BIKE_PARKING"
  | "GAS_STATION"
  | "GENERIC";

export interface Poi {
  /** Stable id, e.g. "osm:node:123456". */
  id: string;
  lat: number;
  lng: number;
  type: PoiType;
  name: string | null;
  /** Meters along the route where this POI is nearest. Empty for /nearby. */
  distancesAlongRoute: number[];
  /** Passthrough of useful OSM tags (opening_hours, website, phone, …). */
  tags: Record<string, string>;
}

// ---- Request bodies ----

export interface BuildRequest {
  /** Google encoded polyline, precision 5 (as delivered by karoo-ext). */
  polyline: string;
  /** Detour search radius around the route, in meters. */
  detourMeters: number;
  categories: Category[];
}

export interface NearbyRequest {
  lat: number;
  lng: number;
  radiusMeters: number;
  categories: Category[];
}

export interface BuildResponse {
  pois: Poi[];
}

// ---- Chunked build protocol ----
// The Karoo HTTP bridge caps a response at 100K, so builds are delivered in pages.
// 1) POST /build/start → compute + store, return the handle below.
// 2) GET /build/:id/page/:n → a page of (trimmed) POIs, each well under 100K.

/** Response to POST /build/start (and /nearby/start). Small; just metadata. */
export interface BuildStartResponse {
  buildId: string;
  totalCount: number;
  pageSize: number;
  pageCount: number;
}

/**
 * Minimal POI shape sent in pages — only what the map needs. Heavy `tags` are
 * kept server-side and omitted here to stay under the size cap.
 */
export interface PagePoi {
  id: string;
  lat: number;
  lng: number;
  type: PoiType;
  name: string | null;
  distancesAlongRoute: number[];
}

export interface BuildPageResponse {
  page: number;
  pois: PagePoi[];
}

/** POIs per page — sized so a page stays comfortably under the 100K cap. */
export const PAGE_SIZE = 250;
