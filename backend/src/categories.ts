// Category → OSM tag filters → Symbol.POI type.
// This is the core of the two custom features: category toggles select which
// tag groups get queried, and each OSM element resolves to a Symbol.POI type.

import type { Category, PoiType } from "./contract.js";

/** One OSM tag filter, e.g. amenity=cafe, and the POI type it resolves to. */
export interface TagRule {
  key: string;
  value: string;
  type: PoiType;
}

export const CATEGORY_RULES: Record<Category, TagRule[]> = {
  restaurants: [
    { key: "amenity", value: "restaurant", type: "FOOD" },
    { key: "amenity", value: "fast_food", type: "FOOD" },
  ],
  supermarkets: [
    { key: "shop", value: "supermarket", type: "CONVENIENCE_STORE" },
    { key: "shop", value: "convenience", type: "CONVENIENCE_STORE" },
  ],
  cafe_bar: [
    { key: "amenity", value: "cafe", type: "COFFEE" },
    { key: "amenity", value: "bar", type: "BAR" },
    { key: "amenity", value: "pub", type: "BAR" },
  ],
  water: [
    { key: "amenity", value: "drinking_water", type: "REST_STOP" },
  ],
  toilet: [
    { key: "amenity", value: "toilets", type: "RESTROOM" },
  ],
  bike: [
    { key: "shop", value: "bicycle", type: "BIKE_SHOP" },
    // bicycle_parking removed: ~28k mostly-unnamed points, pure map clutter.
  ],
  fuel: [{ key: "amenity", value: "fuel", type: "GAS_STATION" }],
};

/** All rules for the enabled categories, flattened. */
export function rulesFor(categories: Category[]): TagRule[] {
  return categories.flatMap((c) => CATEGORY_RULES[c] ?? []);
}

/** Resolve an OSM element's tags to a PoiType, or null if none of ours match. */
export function resolveType(
  tags: Record<string, string>,
  rules: TagRule[],
): PoiType | null {
  for (const r of rules) {
    if (tags[r.key] === r.value) return r.type;
  }
  return null;
}

/** Every tag rule, tagged with the Category it belongs to. Used by the pipeline. */
export const ALL_RULES: Array<TagRule & { category: Category }> = (
  Object.entries(CATEGORY_RULES) as [Category, TagRule[]][]
).flatMap(([category, rules]) => rules.map((r) => ({ ...r, category })));

/** Resolve OSM tags to our {type, category}, or null if none match. */
export function resolvePoi(
  tags: Record<string, string>,
): { type: PoiType; category: Category } | null {
  for (const r of ALL_RULES) {
    if (tags[r.key] === r.value) {
      // Some car dealerships (shop=car) have an on-site pump tagged amenity=fuel.
      // Those aren't public gas stations — exclude them from the fuel category.
      if (
        r.category === "fuel" &&
        (tags.shop === "car" || tags.shop === "car_repair")
      ) {
        continue;
      }
      return { type: r.type, category: r.category };
    }
  }
  return null;
}
