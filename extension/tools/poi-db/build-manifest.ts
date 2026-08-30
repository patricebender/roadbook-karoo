// Emit dist/manifest.json describing the downloadable region files.
//
//   npx tsx build-manifest.ts
//
// Introspects every dist/<id>-v<schema>.sqlite.gz produced by build-region-file.sh:
// gunzips to read poiCount + schema version, records raw/gz sizes and sha256, and joins
// with regions.ts for label/group. The app fetches this manifest, then downloads
// `baseUrl + file` for the region the rider picks.
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
  const schemaVersion = [...schemas][0];

  // Order regions by the catalog order (Germany Complete first, then states, then
  // countries) so the manifest reads naturally; the app groups by `group` anyway.
  const order = new Map(REGIONS.map((r, i) => [r.id, i]));
  regions.sort((a, b) => (order.get(a.id) ?? 0) - (order.get(b.id) ?? 0));

  const manifest = { schemaVersion, baseUrl, regions };
  const out = join(DIST, "manifest.json");
  writeFileSync(out, JSON.stringify(manifest, null, 2) + "\n");
  console.log(
    `wrote ${out}: ${regions.length} region(s), schemaVersion=${schemaVersion}, baseUrl=${baseUrl}`,
  );
}

main();
