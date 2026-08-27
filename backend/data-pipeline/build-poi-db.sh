#!/usr/bin/env bash
# Build the POI SQLite database from an OpenStreetMap regional extract.
#
# Pipeline: download .osm.pbf → osmium tags-filter to our POI tags → osmium export
# to GeoJSONSeq → Node loader writes pois.sqlite (poi table + R*Tree index).
#
# Re-runnable. Region is overridable so scaling from a Bundesland to all of
# Germany/DACH is a single env change.
#
#   OSM_REGION=europe/germany/baden-wuerttemberg ./build-poi-db.sh
set -euo pipefail

REGION="${OSM_REGION:-europe/germany/baden-wuerttemberg}"
WORK="${WORK_DIR:-$(cd "$(dirname "$0")" && pwd)/work}"
OUT="${OUT_DB:-$(cd "$(dirname "$0")/.." && pwd)/pois.sqlite}"

PBF="$WORK/region.osm.pbf"
FILTERED="$WORK/pois.osm.pbf"
GEOJSON="$WORK/pois.geojsonseq"
URL="https://download.geofabrik.de/${REGION}-latest.osm.pbf"

mkdir -p "$WORK"

echo ">> Region: $REGION"
echo ">> Downloading $URL"
curl -fsSL --retry 3 -o "$PBF" "$URL"
echo "   $(du -h "$PBF" | cut -f1) downloaded"

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
  nwr/amenity=fuel
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
