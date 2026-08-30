#!/usr/bin/env bash
# Build a *distributable* per-region POI file for on-demand in-app download.
#
# Distinct from the bundled APK asset (build-poi-db.sh / build-multi-region.sh, which
# stay as-is): this emits a gzipped, R*Tree-stripped SQLite the app fetches over the
# Karoo HTTP bridge and installs, rebuilding the R*Tree on-device. Stripping the
# derivable index + gzip is the size lever (BW+Hessen 19 MB → ~4.5 MB).
#
# Region is a regionId from regions.ts (not a raw Geofabrik path):
#
#   ./build-region-file.sh germany-hessen
#   ./build-region-file.sh germany            # Complete: merges all 16 Bundesländer
#
# Output: dist/<regionId>-v<schemaVersion>.sqlite.gz  (+ echoed sha256/sizes/count for
# the manifest builder).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=merge-lib.sh
source "$HERE/merge-lib.sh"

REGION_ID="${1:-}"
if [[ -z "$REGION_ID" ]]; then
  echo "usage: build-region-file.sh <regionId>   (see: npx tsx regions.ts ids)" >&2
  exit 1
fi

WORK="${WORK_DIR:-$HERE/work}"
DIST="${DIST_DIR:-$HERE/dist}"
mkdir -p "$WORK" "$DIST"

# Resolve the region's Geofabrik path(s) from the catalog.
paths="$(npx --yes tsx "$HERE/regions.ts" paths "$REGION_ID")"
read -ra PATH_ARR <<< "$paths"
echo ">> Region $REGION_ID -> ${#PATH_ARR[@]} extract(s): $paths"

# Build each extract into its own DB (reusing the single-region pipeline), then merge.
# The per-extract DB is cached by slug: when the all-regions run builds `germany` (all 16
# Bundesländer) and then each `germany-<state>` individually, the second pass reuses the
# DB built in the first instead of re-downloading + re-processing the same extract.
# REBUILD=1 forces a fresh build; the pipeline's own FORCE_DOWNLOAD refreshes the extract.
built=()
for gf in "${PATH_ARR[@]}"; do
  slug="$(echo "$gf" | tr '/' '-')"
  db="$WORK/$slug.sqlite"
  if [[ "${REBUILD:-0}" != "1" && -s "$db" ]]; then
    echo "==> Reusing cached $db (REBUILD=1 to force)"
  else
    echo "==> Building $gf -> $db"
    OSM_REGION="$gf" OUT_DB="$db" FORCE_DOWNLOAD="${FORCE_DOWNLOAD:-0}" "$HERE/build-poi-db.sh"
  fi
  built+=("$db")
done

# Assemble the region DB (single build → just that DB; multi → offset-merge + dedup).
ASSEMBLED="$WORK/$REGION_ID.assembled.sqlite"
rm -f "$ASSEMBLED"
echo "==> Assembling $REGION_ID"
merge_dbs "$ASSEMBLED" "${built[@]}"

# Schema version is whatever the pipeline stamped (load-into-sqlite.ts user_version);
# read it rather than hardcode a third copy. Must match the app's BUNDLED_DB_VERSION.
SCHEMA="$(sqlite3 "$ASSEMBLED" 'PRAGMA user_version;')"
if [[ -z "$SCHEMA" || "$SCHEMA" == "0" ]]; then
  echo "!! assembled DB has user_version=$SCHEMA; expected the pipeline's schema version" >&2
  exit 1
fi

# Stamp every row with this region's id (provenance for additive install + removal on
# device), then strip the derivable R*Tree + category index; the app rebuilds both on
# install. VACUUM reclaims the freed pages (the real size win) and clears user_version →
# restore.
DISTDB="$WORK/$REGION_ID.dist.sqlite"
cp "$ASSEMBLED" "$DISTDB"
echo "==> Stamping region_id=$REGION_ID, stripping R*Tree + index, VACUUM (schema v$SCHEMA)"
sqlite3 "$DISTDB" <<SQL
UPDATE poi SET region_id='$REGION_ID';
DROP TABLE IF EXISTS poi_rtree;
DROP INDEX IF EXISTS idx_poi_category;
VACUUM;
PRAGMA user_version=$SCHEMA;
SQL

POIS="$(sqlite3 "$DISTDB" 'SELECT COUNT(*) FROM poi;')"
INTEGRITY="$(sqlite3 "$DISTDB" 'PRAGMA integrity_check;')"
if [[ "$INTEGRITY" != "ok" ]]; then
  echo "!! integrity_check failed: $INTEGRITY" >&2
  exit 1
fi

# gzip to the distributable artifact.
OUT="$DIST/$REGION_ID-v$SCHEMA.sqlite.gz"
gzip -9 -c "$DISTDB" > "$OUT"

RAW="$(wc -c < "$DISTDB" | tr -d ' ')"
GZ="$(wc -c < "$OUT" | tr -d ' ')"
SHA="$(shasum -a 256 "$OUT" | cut -d' ' -f1)"

echo ">> Done: $OUT"
echo "   poiCount=$POIS bytesRaw=$RAW bytesGz=$GZ schema=$SCHEMA"
echo "   sha256=$SHA"
