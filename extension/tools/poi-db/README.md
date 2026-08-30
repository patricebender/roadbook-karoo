# POI database builder

Builds the on-device POI database bundled with the extension
(`app/src/main/assets/pois-baden-wuerttemberg.sqlite`) from an OpenStreetMap extract.
The app seeds from this asset on first run and re-seeds when its `PRAGMA user_version`
increases.

## Requirements

- `osmium` (`brew install osmium-tool`)
- Node ≥ 20

## Build

```bash
npm install
npm run build:poi-db
```

This downloads the region extract, filters it to our POI tags, exports to GeoJSONSeq,
and writes the SQLite asset (poi table + R*Tree spatial index, with allowlisted OSM
tags such as `opening_hours`/`website`).

### Options

- `OSM_REGION` — Geofabrik path, default `europe/germany/baden-wuerttemberg`. Change it
  to widen coverage, e.g. `OSM_REGION=europe/germany npm run build:poi-db`.
- `FORCE_DOWNLOAD=1` — re-download the extract even if a cached `work/region.osm.pbf`
  exists (downloads are skipped by default to save the ~230 MB fetch).
- `OUT_DB` — override the output path (defaults to the bundled asset).

Intermediate files live in `work/` (gitignored scratch); safe to delete.

### Multiple regions

The app seeds from a single asset, so covering more than one Bundesland means building
each region and merging them into that one asset:

```bash
npm run build:poi-db:multi                       # default: Baden-Württemberg + Hessen
REGIONS="europe/germany/baden-wuerttemberg europe/germany/hessen europe/germany/rheinland-pfalz" \
  npm run build:poi-db:multi                     # widen coverage
```

`build-multi-region.sh` builds each region into `work/<slug>.sqlite`, then merges them
into the bundled asset — re-keying ids/rtree rowids per region and dropping the
cross-region duplicate `osm_id`s Geofabrik ships on shared boundaries (the device's
unique index on `osm_id` would otherwise reject the seed). Plain `build:poi-db`
overwrites the asset with a single region, so use the `:multi` variant to preserve
multi-region coverage.

## Downloadable regions (on-demand, in-app)

The bundled asset above is the offline-first seed. Riders can also download other
regions in-app (Germany Complete + each Bundesland, plus whole countries). Those files
are built here and uploaded to a GitHub Release; the app fetches them over the Karoo
HTTP bridge and installs them, rebuilding the R\*Tree on-device.

```bash
npm run build:regions                             # all regions in regions.ts
REGIONS_IDS="germany-bremen italy" npm run build:regions   # a subset
```

`build-all-regions.sh` does three things:

1. **Regenerates `app/src/main/assets/regions.json`** (the picker's catalog) from
   `regions.ts` — the single source of truth for region id/label/group. Commit this.
2. **Builds each region file** via `build-region-file.sh <id>`: runs the same pipeline
   (single extract, or a merge of all 16 Bundesländer for `germany`), then strips the
   derivable R\*Tree + category index and `VACUUM`s (the app rebuilds them on install —
   this is the size lever), and `gzip -9`s to `dist/<id>-v<schema>.sqlite.gz`.
3. **Emits `dist/manifest.json`** (`build-manifest.ts`): per-region file, gzipped/raw
   sizes, POI count, and sha256 (verified on-device after download). **Cumulative** — if
   a `dist/manifest.json` already exists it carries those regions forward and overlays the
   ones built this run, so a subset build extends the manifest instead of replacing it
   (prior entries are dropped only on a schema mismatch). Pre-seed `dist/` with the
   published manifest to accumulate onto a release; the CI workflow does this automatically.

`dist/` is gitignored — it's release output, not committed. See `docs/releasing.md` for
the upload step (and the cumulative caveat for local subset builds). The manifest's `baseUrl` is env-overridable
(`REGIONS_BASE_URL=... npm run build:manifest`) so a release retag doesn't need a code
change; it defaults to the `regions-latest` release.

`regions.ts` is a tiny CLI too: `npx tsx regions.ts ids`, `... paths <id>`,
`... app-json`.

## Bumping the DB version

When the schema or tag allowlist changes, bump **all three** in lockstep so installed
apps re-seed and downloaded region files stay compatible:

- `db.pragma("user_version = N")` in `load-into-sqlite.ts`
- `BUNDLED_DB_VERSION = N` in `data/PoiDatabase.kt`
- rebuild + re-upload the region files (their `manifest.json` `schemaVersion` must match
  `BUNDLED_DB_VERSION`, or the app refuses the download and asks the rider to update)

## Keep in sync

`categories.ts` / `contract.ts` mirror the Kotlin `Category` enum
(`data/RoadbookConfig.kt`) and the karoo-ext `Symbol.POI.Types`. Changing categories
here means changing them there too.
