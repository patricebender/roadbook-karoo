#!/usr/bin/env bash
# Shared merge/dedup logic for combining several per-region POI SQLite DBs into one.
#
# Sourced by build-multi-region.sh (builds the bundled APK asset) and
# build-region-file.sh (builds a distributable region file, e.g. Germany Complete), so
# both get identical merge semantics. Handles the two things a naive concat gets wrong:
#   1. id / poi_rtree rowid collisions — each per-region DB numbers from 1, so every
#      region after the first is offset by the running max id.
#   2. cross-region duplicate osm_ids — Geofabrik ships boundary features in both
#      adjacent extracts; the device's UNIQUE INDEX on osm_id would reject them, so we
#      keep the first copy (lowest id) and drop the rest.
#
# VACUUM clears user_version, so it's captured from the base DB and restored at the end.

# merge_dbs OUT BASE_DB [MORE_DB ...]
#   Copies BASE_DB to OUT, appends each MORE_DB with an id offset, de-dups boundary
#   osm_ids, VACUUMs, and restores user_version. Single-DB input is valid (just copy +
#   VACUUM). Echoes a one-line summary.
merge_dbs() {
  local out="$1"; shift
  local base="$1"; shift

  cp "$base" "$out"
  local version
  version="$(sqlite3 "$out" 'PRAGMA user_version;')"

  local db offset
  for db in "$@"; do
    offset="$(sqlite3 "$out" 'SELECT COALESCE(MAX(id),0) FROM poi;')"
    echo "   + $(basename "$db") (id offset $offset)"
    sqlite3 "$out" <<SQL
ATTACH '$db' AS r;
BEGIN;
INSERT INTO poi (id, osm_id, lat, lng, type, category, name, tags, region_id)
  SELECT id + $offset, osm_id, lat, lng, type, category, name, tags, region_id FROM r.poi;
INSERT INTO poi_rtree (id, minLat, maxLat, minLng, maxLng)
  SELECT id + $offset, minLat, maxLat, minLng, maxLng FROM r.poi_rtree;
COMMIT;
DETACH r;
SQL
  done

  # Drop cross-region duplicate osm_ids (keep the lowest id), mirror into the rtree.
  if [[ $# -gt 0 ]]; then
    echo "   de-duplicating boundary osm_ids"
    sqlite3 "$out" <<'SQL'
BEGIN;
DELETE FROM poi_rtree WHERE id IN (
  SELECT id FROM poi WHERE id NOT IN (SELECT MIN(id) FROM poi GROUP BY osm_id)
);
DELETE FROM poi WHERE id NOT IN (SELECT MIN(id) FROM poi GROUP BY osm_id);
COMMIT;
SQL
  fi

  # VACUUM clears user_version, so restore what the base DB carried.
  sqlite3 "$out" "VACUUM; PRAGMA user_version=$version;"

  local pois integrity
  pois="$(sqlite3 "$out" 'SELECT COUNT(*) FROM poi;')"
  integrity="$(sqlite3 "$out" 'PRAGMA integrity_check;')"
  echo "   merged: $pois POIs, user_version=$version, integrity=$integrity"
}
