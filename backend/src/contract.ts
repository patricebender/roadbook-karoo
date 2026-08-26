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
