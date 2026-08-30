#!/usr/bin/env bash
# Build the POI SQLite database from an OpenStreetMap regional extract.
#
# Pipeline: download .osm.pbf → osmium tags-filter to our POI tags → osmium export
# to GeoJSONSeq → Node loader writes the bundled extension asset directly (poi table
# + R*Tree index).
#
# Re-runnable. Region is overridable so scaling from a Bundesland to all of
# Germany/DACH is a single env change.
#
#   OSM_REGION=europe/germany/baden-wuerttemberg ./build-poi-db.sh
set -euo pipefail

REGION="${OSM_REGION:-europe/germany/baden-wuerttemberg}"
WORK="${WORK_DIR:-$(cd "$(dirname "$0")" && pwd)/work}"
# Single-region build output. Callers override OUT_DB: build-region-file.sh routes it
# into per-region work DBs (the seed + downloadable files are built from those, not from
# this default). Defaults to scratch so a bare run doesn't write a dead asset.
OUT="${OUT_DB:-$WORK/pois.sqlite}"

# Cache the extract + intermediates per-region (slug from the Geofabrik path) rather than
# a shared region.osm.pbf. The germany build downloads all 16 Bundesland extracts; caching
# by slug lets the subsequent per-Bundesland builds reuse them instead of re-fetching the
# same ~2.5 GB (the "downloads each Bundesland twice" waste in the all-regions run).
SLUG="$(echo "$REGION" | tr '/' '-')"
PBF="$WORK/$SLUG.osm.pbf"
FILTERED="$WORK/$SLUG.pois.osm.pbf"
GEOJSON="$WORK/$SLUG.pois.geojsonseq"
URL="https://download.geofabrik.de/${REGION}-latest.osm.pbf"

mkdir -p "$WORK"

echo ">> Region: $REGION"
# Reuse a previously downloaded extract; the BW pbf is ~230 MB. Set FORCE_DOWNLOAD=1
# to refresh. Skips only when a non-empty file is already present.
if [[ "${FORCE_DOWNLOAD:-0}" != "1" && -s "$PBF" ]]; then
  echo ">> Using cached $PBF ($(du -h "$PBF" | cut -f1)); FORCE_DOWNLOAD=1 to refresh"
else
  echo ">> Downloading $URL"
  curl -fsSL --retry 3 -o "$PBF" "$URL"
  echo "   $(du -h "$PBF" | cut -f1) downloaded"
fi

# Our POI tags, filtered across nodes+ways+relations (POIs are mapped as any of them).
echo ">> Filtering to POI tags"
osmium tags-filter --overwrite -o "$FILTERED" "$PBF" \
  nwr/amenity=cafe \
  nwr/amenity=restaurant \
  nwr/amenity=fast_food \
  nwr/amenity=bar \
  nwr/amenity=pub \
  nwr/shop=convenience \
  nwr/shop=supermarket \
  nwr/amenity=drinking_water \
  nwr/amenity=toilets \
  nwr/shop=bicycle \
  nwr/amenity=bicycle_parking \
  nwr/amenity=fuel \
  nwr/amenity=ice_cream \
  nwr/shop=ice_cream
echo "   $(du -h "$FILTERED" | cut -f1) after filter"

# Export to GeoJSONSeq; ways/relations become a representative point (centroid).
echo ">> Exporting to GeoJSONSeq"
osmium export --overwrite -o "$GEOJSON" -f geojsonseq \
  --add-unique-id=type_id \
  -c <(echo '{"area":{"attributes":true},"linear":{"attributes":true}}') \
  "$FILTERED" 2>/dev/null || \
osmium export --overwrite -o "$GEOJSON" -f geojsonseq \
  --add-unique-id=type_id "$FILTERED"

LINES=$(wc -l < "$GEOJSON" | tr -d ' ')
echo "   $LINES features exported"

# Load into SQLite.
echo ">> Loading into $OUT"
npx tsx "$(dirname "$0")/load-into-sqlite.ts" "$GEOJSON" "$OUT"

echo ">> Done: $(du -h "$OUT" | cut -f1) at $OUT"
