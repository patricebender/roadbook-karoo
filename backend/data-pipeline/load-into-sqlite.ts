// Load a GeoJSONSeq of filtered OSM POIs into a spatial SQLite database.
//
//   npx tsx load-into-sqlite.ts <input.geojsonseq> <output.sqlite>
//
// Produces:
//   poi(id, lat, lng, type, category, name)  — one row per POI
//   poi_rtree(rowid, minLat, maxLat, minLng, maxLng)  — R*Tree spatial index
//
// Type/category come from the shared category rules so the map pins match.

import Database from "better-sqlite3";
import { createReadStream } from "node:fs";
import { createInterface } from "node:readline";
import { resolvePoi } from "../src/categories.js";

interface GeoJsonFeature {
  type: "Feature";
  id?: string; // osmium --add-unique-id=type_id puts e.g. "n16257496" here
  geometry: { type: string; coordinates: unknown };
  properties: Record<string, string>;
}

// Types worth keeping even when unnamed — given a generic label instead of dropping.
const DEFAULT_NAME: Record<string, string> = {
  REST_STOP: "Water", // amenity=drinking_water
  RESTROOM: "Toilet", // amenity=toilets
};

/** Representative [lng, lat] for a feature: point directly, else polygon/line centroid. */
function representativePoint(geom: GeoJsonFeature["geometry"]): [number, number] | null {
  const c = geom.coordinates;
  if (geom.type === "Point") return c as [number, number];
  // Flatten nested coordinate arrays and average — good enough for a pin.
  const pts: number[][] = [];
  const walk = (a: unknown): void => {
    if (Array.isArray(a) && typeof a[0] === "number") {
      pts.push(a as number[]);
    } else if (Array.isArray(a)) {
      a.forEach(walk);
    }
  };
  walk(c);
  if (pts.length === 0) return null;
  const sum = pts.reduce((acc, p) => [acc[0] + p[0]!, acc[1] + p[1]!], [0, 0]);
  return [sum[0] / pts.length, sum[1] / pts.length];
}

async function main() {
  const [input, output] = process.argv.slice(2);
  if (!input || !output) {
    console.error("usage: load-into-sqlite.ts <input.geojsonseq> <output.sqlite>");
    process.exit(1);
  }

  const db = new Database(output);
  // DELETE journal (not WAL): the output is a self-contained file meant to be
  // copied/bundled as a read-only asset, with no -wal/-shm sidecars.
  db.pragma("journal_mode = DELETE");
  db.exec(`
    DROP TABLE IF EXISTS poi;
    DROP TABLE IF EXISTS poi_rtree;
    CREATE TABLE poi (
      id       INTEGER PRIMARY KEY,
      osm_id   TEXT NOT NULL,
      lat      REAL NOT NULL,
      lng      REAL NOT NULL,
      type     TEXT NOT NULL,
      category TEXT NOT NULL,
      name     TEXT
    );
    CREATE VIRTUAL TABLE poi_rtree USING rtree(id, minLat, maxLat, minLng, maxLng);
  `);

  const insertPoi = db.prepare(
    "INSERT INTO poi (id, osm_id, lat, lng, type, category, name) VALUES (?, ?, ?, ?, ?, ?, ?)",
  );
  const insertRtree = db.prepare(
    "INSERT INTO poi_rtree (id, minLat, maxLat, minLng, maxLng) VALUES (?, ?, ?, ?, ?)",
  );

  let rowId = 0;
  let skipped = 0;
  const seen = new Set<string>();

  const load = db.transaction((features: GeoJsonFeature[]) => {
    for (const f of features) {
      const resolved = resolvePoi(f.properties ?? {});
      if (!resolved) {
        skipped++;
        continue;
      }
      const pt = representativePoint(f.geometry);
      if (!pt) {
        skipped++;
        continue;
      }
      const [lng, lat] = pt;

      // Quality filter: drop unnamed POIs (they render as a meaningless "Pin"),
      // EXCEPT water/toilets which are useful even without a name — give those a
      // generic label. This is the biggest lever against map clutter.
      const rawName = f.properties["name"] ?? null;
      let name = rawName;
      if (rawName == null) {
        name = DEFAULT_NAME[resolved.type] ?? null;
        if (name == null) {
          skipped++;
          continue; // unnamed and not a labelable type → drop
        }
      }

      const osmId = String(f.id ?? `${lat},${lng}`);
      if (seen.has(osmId)) continue;
      seen.add(osmId);

      rowId++;
      insertPoi.run(rowId, osmId, lat, lng, resolved.type, resolved.category, name);
      insertRtree.run(rowId, lat, lat, lng, lng);
    }
  });

  const batch: GeoJsonFeature[] = [];
  const rl = createInterface({ input: createReadStream(input), crlfDelay: Infinity });
  for await (const line of rl) {
    const trimmed = line.trim();
    if (!trimmed || trimmed === "\x1e") continue; // GeoJSONSeq record separator
    // geojsonseq may prefix each line with the RS (0x1e) control char.
    const json = trimmed.replace(/^\x1e/, "");
    try {
      batch.push(JSON.parse(json));
    } catch {
      skipped++;
    }
    if (batch.length >= 10_000) {
      load(batch.splice(0));
    }
  }
  if (batch.length) load(batch);

  db.exec("CREATE INDEX idx_poi_category ON poi(category);");
  const count = (db.prepare("SELECT COUNT(*) AS n FROM poi").get() as { n: number }).n;
  db.close();
  console.log(`loaded ${count} POIs (skipped ${skipped} non-matching/invalid)`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
