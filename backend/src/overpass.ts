// Overpass API client + query builder.
// MVP: public Overpass endpoint with a simple in-memory cache. Migration path to
// self-hosted Overpass is a single OVERPASS_URL env change.

import type { Category, Poi } from "./contract.js";
import { rulesFor, resolveType } from "./categories.js";
import {
  type LatLng,
  cumulativeDistances,
  downsample,
  nearestAlongRoute,
} from "./polyline.js";

const OVERPASS_URL =
  process.env.OVERPASS_URL ?? "https://overpass-api.de/api/interpreter";
const USER_AGENT =
  process.env.OVERPASS_USER_AGENT ??
  "roadbook-karoo/0.1 (personal use; contact via github)";

interface OverpassElement {
  type: "node" | "way" | "relation";
  id: number;
  lat?: number;
  lon?: number;
  center?: { lat: number; lon: number };
  tags?: Record<string, string>;
}

interface OverpassResponse {
  elements: OverpassElement[];
}

/** Tags we pass through to the client if present. */
const KEEP_TAGS = [
  "name",
  "opening_hours",
  "website",
  "phone",
  "operator",
  "cuisine",
  "brand",
];

/**
 * Build an Overpass QL query. For each enabled tag rule we search nodes+ways
 * within `radius` meters of every point in `pts` (the route corridor, or a single
 * point for /nearby). `out center` gives ways a representative coordinate.
 */
export function buildQuery(
  pts: LatLng[],
  radiusMeters: number,
  categories: Category[],
  timeoutSec = 60,
): string {
  const rules = rulesFor(categories);
  const coords = pts.map((p) => `${p.lat},${p.lng}`).join(",");
  const clauses = rules
    .map(
      (r) =>
        `  nwr(around:${radiusMeters},${coords})["${r.key}"="${r.value}"];`,
    )
    .join("\n");
  return `[out:json][timeout:${timeoutSec}];\n(\n${clauses}\n);\nout center tags;`;
}

async function runOverpass(query: string): Promise<OverpassResponse> {
  const res = await fetch(OVERPASS_URL, {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      "User-Agent": USER_AGENT,
    },
    body: "data=" + encodeURIComponent(query),
  });
  if (!res.ok) {
    throw new Error(`Overpass ${res.status}: ${await res.text()}`);
  }
  return (await res.json()) as OverpassResponse;
}

function elementLatLng(el: OverpassElement): LatLng | null {
  if (el.lat != null && el.lon != null) return { lat: el.lat, lng: el.lon };
  if (el.center) return { lat: el.center.lat, lng: el.center.lon };
  return null;
}

function pickTags(tags: Record<string, string>): Record<string, string> {
  const out: Record<string, string> = {};
  for (const k of KEEP_TAGS) if (tags[k] != null) out[k] = tags[k]!;
  return out;
}

/** Build roadbook POIs along a route corridor. */
export async function buildAlongRoute(
  route: LatLng[],
  detourMeters: number,
  categories: Category[],
): Promise<Poi[]> {
  // Overlap circles: sample the route at ~detour spacing so the corridor is covered.
  const pts = downsample(route, Math.max(detourMeters, 100));
  const query = buildQuery(pts, detourMeters, categories);
  const resp = await runOverpass(query);

  const cumulative = cumulativeDistances(route);
  const rules = rulesFor(categories);
  const seen = new Set<string>();
  const pois: Poi[] = [];

  for (const el of resp.elements) {
    const ll = elementLatLng(el);
    const tags = el.tags ?? {};
    if (!ll) continue;
    const type = resolveType(tags, rules);
    if (!type) continue;
    const id = `osm:${el.type}:${el.id}`;
    if (seen.has(id)) continue;
    seen.add(id);
    const { distanceAlong } = nearestAlongRoute(route, cumulative, ll);
    pois.push({
      id,
      lat: ll.lat,
      lng: ll.lng,
      type,
      name: tags.name ?? null,
      distancesAlongRoute: [Math.round(distanceAlong)],
      tags: pickTags(tags),
    });
  }

  pois.sort(
    (a, b) => (a.distancesAlongRoute[0] ?? 0) - (b.distancesAlongRoute[0] ?? 0),
  );
  return pois;
}

/** Build POIs near a single point (on-demand fallback). */
export async function buildNearby(
  center: LatLng,
  radiusMeters: number,
  categories: Category[],
): Promise<Poi[]> {
  const query = buildQuery([center], radiusMeters, categories);
  const resp = await runOverpass(query);
  const rules = rulesFor(categories);
  const seen = new Set<string>();
  const pois: Poi[] = [];

  for (const el of resp.elements) {
    const ll = elementLatLng(el);
    const tags = el.tags ?? {};
    if (!ll) continue;
    const type = resolveType(tags, rules);
    if (!type) continue;
    const id = `osm:${el.type}:${el.id}`;
    if (seen.has(id)) continue;
    seen.add(id);
    pois.push({
      id,
      lat: ll.lat,
      lng: ll.lng,
      type,
      name: tags.name ?? null,
      distancesAlongRoute: [],
      tags: pickTags(tags),
    });
  }
  return pois;
}
