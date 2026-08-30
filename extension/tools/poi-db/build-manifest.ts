// Emit dist/manifest.json describing the downloadable region files.
//
//   npx tsx build-manifest.ts
//
// Introspects every dist/<id>-v<schema>.sqlite.gz produced by build-region-file.sh:
// gunzips to read poiCount + schema version, records raw/gz sizes and sha256, and joins
// with regions.ts for label/group. The app fetches this manifest, then downloads
// `baseUrl + file` for the region the rider picks.
//
// Cumulative: if dist/manifest.json already exists (the release workflow pre-downloads
// the published one), its regions are carried forward and the freshly-built ones are
// merged on top. So building a subset adds/refreshes just those regions without dropping
// the rest — the release accumulates coverage instead of last-run-wins. Prior entries are
// only kept when their schema matches this run's; a schema bump discards them (everything
// must be rebuilt then anyway).
//
// baseUrl points at the GitHub release hosting the assets. It's env-overridable so a
// release retag (or a host change) doesn't require editing this file:
//
//   REGIONS_BASE_URL="https://github.com/OWNER/REPO/releases/download/regions-v3/" \
//     npx tsx build-manifest.ts

import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { gunzipSync } from "node:zlib";
import { mkdtempSync, readFileSync, readdirSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { REGIONS, regionById } from "./regions.js";

const HERE = new URL(".", import.meta.url).pathname;
const DIST = process.env.DIST_DIR ?? join(HERE, "dist");

const DEFAULT_BASE_URL =
  "https://github.com/patricebender/roadbook-karoo/releases/download/regions-latest/";
const baseUrl = (process.env.REGIONS_BASE_URL ?? DEFAULT_BASE_URL).replace(/\/?$/, "/");

interface ManifestRegion {
  id: string;
  label: string;
  group: string;
  file: string;
  bytesGz: number;
  bytesRaw: number;
  poiCount: number;
  sha256: string;
}

interface Manifest {
  schemaVersion: number;
  baseUrl: string;
  regions: ManifestRegion[];
}

/**
 * Regions from a previously published manifest sitting in DIST, or [] if there's none /
 * it's unreadable / its schema differs from this run. Lets a subset build accumulate onto
 * the release instead of replacing it.
 */
function priorRegions(schemaVersion: number): ManifestRegion[] {
  const path = join(DIST, "manifest.json");
  let prior: Manifest;
  try {
    prior = JSON.parse(readFileSync(path, "utf8")) as Manifest;
  } catch {
    return []; // no prior manifest (first run) → nothing to carry forward
  }
  if (prior.schemaVersion !== schemaVersion) {
    console.warn(
      `prior manifest schemaVersion=${prior.schemaVersion} != ${schemaVersion}; ` +
        `discarding its ${prior.regions?.length ?? 0} region(s) (schema bump).`,
    );
    return [];
  }
  return prior.regions ?? [];
}

/** Parse `<id>-v<schema>.sqlite.gz` → { id, schema }. Returns null for other files. */
function parseArtifact(file: string): { id: string; schema: number } | null {
  const m = /^(.+)-v(\d+)\.sqlite\.gz$/.exec(file);
  if (!m || m[1] == null || m[2] == null) return null;
  return { id: m[1], schema: Number(m[2]) };
}

/** POI count of a gunzipped SQLite buffer, via a throwaway temp file + sqlite3. */
function poiCountOf(rawDb: Buffer): number {
  const dir = mkdtempSync(join(tmpdir(), "manifest-"));
  const path = join(dir, "r.sqlite");
  try {
    writeFileSync(path, rawDb);
    const out = execFileSync("sqlite3", [path, "SELECT COUNT(*) FROM poi;"], {
      encoding: "utf8",
    });
    return Number(out.trim());
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

function main(): void {
  const files = readdirSync(DIST).filter((f) => f.endsWith(".sqlite.gz"));
  if (files.length === 0) {
    throw new Error(`no *.sqlite.gz in ${DIST} — run build-region-file.sh first`);
  }

  const schemas = new Set<number>();
  const regions: ManifestRegion[] = [];

  for (const file of files.sort()) {
    const parsed = parseArtifact(file);
    if (!parsed) {
      console.warn(`skipping unrecognized artifact: ${file}`);
      continue;
    }
    const region = regionById(parsed.id); // throws on unknown id → catches stale files
    schemas.add(parsed.schema);

    const gz = readFileSync(join(DIST, file));
    const raw = gunzipSync(gz);
    regions.push({
      id: region.id,
      label: region.label,
      group: region.group,
      file,
      bytesGz: gz.length,
      bytesRaw: raw.length,
      poiCount: poiCountOf(raw),
      sha256: createHash("sha256").update(gz).digest("hex"),
    });
  }

  if (schemas.size !== 1) {
    throw new Error(
      `mixed schema versions across artifacts: ${[...schemas].join(", ")}. ` +
        `Rebuild all region files against one schema.`,
    );
  }
  const schemaVersion = [...schemas][0]!;

  // Carry forward regions from a previously published manifest (cumulative release), then
  // overlay this run's freshly-built regions — a rebuilt id replaces its prior entry.
  const builtIds = new Set(regions.map((r) => r.id));
  const merged = [
    ...priorRegions(schemaVersion).filter((r) => !builtIds.has(r.id)),
    ...regions,
  ];

  // Order regions by the catalog order (Germany Complete first, then states, then
  // countries) so the manifest reads naturally; the app groups by `group` anyway.
  const order = new Map(REGIONS.map((r, i) => [r.id, i]));
  merged.sort((a, b) => (order.get(a.id) ?? 0) - (order.get(b.id) ?? 0));

  const manifest = { schemaVersion, baseUrl, regions: merged };
  const out = join(DIST, "manifest.json");
  writeFileSync(out, JSON.stringify(manifest, null, 2) + "\n");
  console.log(
    `wrote ${out}: ${merged.length} region(s) ` +
      `(${regions.length} built this run, ${merged.length - regions.length} carried over), ` +
      `schemaVersion=${schemaVersion}, baseUrl=${baseUrl}`,
  );
}

main();
