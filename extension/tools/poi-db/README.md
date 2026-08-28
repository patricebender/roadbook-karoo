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

## Bumping the DB version

When the schema or tag allowlist changes, bump **both** in lockstep so installed apps
re-seed:

- `db.pragma("user_version = N")` in `load-into-sqlite.ts`
- `BUNDLED_DB_VERSION = N` in `data/PoiDatabase.kt`

## Keep in sync

`categories.ts` / `contract.ts` mirror the Kotlin `Category` enum
(`data/RoadbookConfig.kt`) and the karoo-ext `Symbol.POI.Types`. Changing categories
here means changing them there too.
