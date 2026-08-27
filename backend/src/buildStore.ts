// In-memory store for computed builds, so paged fetches don't re-run Overpass.
// Entries expire after a TTL. Fine for personal/single-instance use; if Cloud Run
// recycles the instance, the entry is lost and the client re-issues /build/start.

import { randomUUID } from "node:crypto";
import type { Poi } from "./contract.js";

interface Entry {
  pois: Poi[];
  createdAt: number;
}

const TTL_MS = Number(process.env.BUILD_TTL_MS ?? 5 * 60 * 1000);
const store = new Map<string, Entry>();

/** Store a computed POI list and return its buildId. */
export function create(pois: Poi[]): string {
  prune();
  const id = randomUUID();
  store.set(id, { pois, createdAt: Date.now() });
  return id;
}

/** Retrieve a build's POIs, or undefined if missing/expired. */
export function get(id: string): Poi[] | undefined {
  const entry = store.get(id);
  if (!entry) return undefined;
  if (Date.now() - entry.createdAt > TTL_MS) {
    store.delete(id);
    return undefined;
  }
  return entry.pois;
}

function prune(): void {
  const now = Date.now();
  for (const [id, entry] of store) {
    if (now - entry.createdAt > TTL_MS) store.delete(id);
  }
}
