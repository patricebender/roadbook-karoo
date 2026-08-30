#!/usr/bin/env bash
# Build the bundled POI asset from *multiple* OSM regions.
#
# The app seeds from a single asset (PoiDatabase.SEED_ASSET), so to cover more than
# one Bundesland we build each region into its own SQLite, then merge them into the
# bundled asset here. Merge handles the two things a naive concat gets wrong:
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
REGIONS="${REGIONS:-europe/germany/baden-wuerttemberg europe/germany/hessen}"
# Same bundled asset build-poi-db.sh targets; the app seeds from this filename.
OUT="${OUT_DB:-$(cd "$HERE/../.." && pwd)/app/src/main/assets/pois-baden-wuerttemberg.sqlite}"
WORK="${WORK_DIR:-$HERE/work}"

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
base="${built[0]}"
echo "==> Merging into $OUT (base: $(basename "$base"))"
cp "$base" "$OUT"
version="$(sqlite3 "$OUT" 'PRAGMA user_version;')"

for db in "${built[@]:1}"; do
  offset="$(sqlite3 "$OUT" 'SELECT COALESCE(MAX(id),0) FROM poi;')"
  echo "   + $(basename "$db") (id offset $offset)"
  sqlite3 "$OUT" <<SQL
ATTACH '$db' AS r;
BEGIN;
INSERT INTO poi (id, osm_id, lat, lng, type, category, name, tags)
  SELECT id + $offset, osm_id, lat, lng, type, category, name, tags FROM r.poi;
INSERT INTO poi_rtree (id, minLat, maxLat, minLng, maxLng)
  SELECT id + $offset, minLat, maxLat, minLng, maxLng FROM r.poi_rtree;
COMMIT;
DETACH r;
SQL
done

# Drop cross-region duplicate osm_ids (keep the lowest id), mirror into the rtree.
echo "==> De-duplicating boundary osm_ids"
sqlite3 "$OUT" <<'SQL'
BEGIN;
DELETE FROM poi_rtree WHERE id IN (
  SELECT id FROM poi WHERE id NOT IN (SELECT MIN(id) FROM poi GROUP BY osm_id)
);
DELETE FROM poi WHERE id NOT IN (SELECT MIN(id) FROM poi GROUP BY osm_id);
COMMIT;
SQL

# VACUUM clears user_version, so restore what the pipeline set on the base DB.
sqlite3 "$OUT" "VACUUM; PRAGMA user_version=$version;"

pois="$(sqlite3 "$OUT" 'SELECT COUNT(*) FROM poi;')"
integrity="$(sqlite3 "$OUT" 'PRAGMA integrity_check;')"
echo "==> Done: $pois POIs, user_version=$version, integrity=$integrity, $(du -h "$OUT" | cut -f1) at $OUT"
