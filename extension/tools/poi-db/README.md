# POI database builder

Builds the on-device POI database bundled with the extension
(`app/src/main/assets/pois-germany.sqlite`) from OpenStreetMap extracts. The bundled
seed is the full **Germany** region file — the same R\*Tree-stripped artifact the picker
downloads (the picker fetches it gzipped; the seed is stored uncompressed, see below). The
app rebuilds the R\*Tree on first run, and re-seeds when its `PRAGMA user_version` increases.

Region installs are **additive**: every `poi` row carries a `region_id` (which download
inserted it), so downloaded regions merge into the live DB (dedup by `osm_id`) instead of
replacing it, and a region can be removed again. The seed's rows are stamped
`region_id=germany`.

Build the bundled seed with `npm run build:seed` (→ `assets/pois-germany.sqlite`).

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

### The bundled seed (`build:seed`)

`npm run build:seed` builds the full Germany region file (via `build-region-file.sh
germany` — assemble all 16 Bundesländer, dedup boundary `osm_id`s, strip the R\*Tree) and
gunzips it into `app/src/main/assets/pois-germany.sqlite`. That's the whole seed pipeline;
the app rebuilds the R\*Tree on first run. It's stored **uncompressed**: AGP's asset merger
auto-inflates + renames any `*.gz` asset, and the APK zip DEFLATEs the entry anyway, so
gzip would only break the asset name the app opens.

`build-multi-region.sh` (`build:poi-db:multi`) is the older generic multi-region merge
into a *raw* asset — no longer the seed path, kept for ad-hoc local DBs.

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
- rebuild the bundled seed (`npm run build:seed`) so the shipped asset is version N
- rebuild + re-upload the region files (their `manifest.json` `schemaVersion` must match
  `BUNDLED_DB_VERSION`, or the app refuses the download and asks the rider to update)

## Keep in sync

`categories.ts` / `contract.ts` mirror the Kotlin `Category` enum
(`data/RoadbookConfig.kt`) and the karoo-ext `Symbol.POI.Types`. Changing categories
here means changing them there too.
