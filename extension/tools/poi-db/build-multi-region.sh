#!/usr/bin/env bash
# Build a merged raw POI SQLite from *multiple* OSM regions (ad-hoc / legacy).
#
# NOT the seed path anymore: the app seeds from the germany region file
# (build-seed.sh -> pois-germany.sqlite). This is kept for building a raw, unstripped
# multi-region DB locally. Each region is built into its own SQLite, then merged. Merge
# handles the two things a naive concat gets wrong:
#   1. id / poi_rtree rowid collisions — each per-region DB numbers from 1, so every
#      region after the first is offset by the running max id.
#   2. cross-region duplicate osm_ids — Geofabrik ships boundary features in both
#      adjacent extracts; the device's UNIQUE INDEX on osm_id would reject them, so we
#      keep the first copy and drop the rest.
#
# Regions are overridable; default is the coverage we ship.
#
#   REGIONS="europe/germany/baden-wuerttemberg europe/germany/hessen" ./build-multi-region.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=merge-lib.sh
source "$HERE/merge-lib.sh"
REGIONS="${REGIONS:-europe/germany/baden-wuerttemberg europe/germany/hessen}"
WORK="${WORK_DIR:-$HERE/work}"
# Scratch output by default (this is no longer a bundled asset); override with OUT_DB.
OUT="${OUT_DB:-$WORK/pois-multi.sqlite}"

mkdir -p "$WORK"
built=()

# Build each region into its own DB. FORCE_DOWNLOAD is honoured for the whole run.
for region in $REGIONS; do
  slug="$(echo "$region" | tr '/' '-')"
  db="$WORK/$slug.sqlite"
  echo "==> Building $region -> $db"
  OSM_REGION="$region" OUT_DB="$db" FORCE_DOWNLOAD="${FORCE_DOWNLOAD:-1}" "$HERE/build-poi-db.sh"
  built+=("$db")
done

# Merge into the bundled asset: first region is the base, the rest are appended.
echo "==> Merging into $OUT (base: $(basename "${built[0]}"))"
merge_dbs "$OUT" "${built[@]}"

echo "==> Done: $(du -h "$OUT" | cut -f1) at $OUT"
