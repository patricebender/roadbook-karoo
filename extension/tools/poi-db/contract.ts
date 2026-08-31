// Shared POI vocabulary for the data pipeline. `type` values must be a subset of
// karoo-ext Symbol.POI.Types so the extension can render them directly as map pins,
// and the Kotlin `Category` enum (data/RoadbookConfig.kt) must stay in sync with the
// `Category` union below.

/** POI category toggles the rider switches on/off when building. */
export type Category =
  | "restaurants"
  | "supermarkets"
  | "cafe_bar"
  | "water"
  | "toilet"
  | "bike"
  | "fuel"
  | "ice_cream"
  | "hotels";

export const ALL_CATEGORIES: Category[] = [
  "restaurants",
  "supermarkets",
  "cafe_bar",
  "water",
  "toilet",
  "bike",
  "fuel",
  "ice_cream",
  "hotels",
];

/** Subset of karoo-ext Symbol.POI.Types we emit. */
export type PoiType =
  | "COFFEE"
  | "FOOD"
  | "BAR"
  | "CONVENIENCE_STORE"
  | "REST_STOP" // drinking water
  | "RESTROOM"
  | "BIKE_SHOP"
  | "GAS_STATION"
  | "ICE_CREAM"
  | "LODGING"
  | "GENERIC";
