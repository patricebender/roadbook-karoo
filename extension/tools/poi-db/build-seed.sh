#!/usr/bin/env bash
# Build the bundled first-run seed asset: the full Germany region file, dropped into the
# app's assets as a gzip. The app gunzips it and rebuilds the R*Tree on first run
# (PoiDatabase.seedFromAsset), so the shipped asset is the same compact, R*Tree-stripped
# file the region picker downloads — no separate build path.
#
#   ./build-seed.sh
#
# Reuses build-region-file.sh germany (assemble 16 Bundesländer → strip → gzip), then
# copies dist/germany-v<schema>.sqlite.gz to app/src/main/assets/pois-germany.sqlite.gz.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP_ASSETS="$(cd "$HERE/../.." && pwd)/app/src/main/assets"
DIST="${DIST_DIR:-$HERE/dist}"
SEED_ID="germany"

echo "==> Building seed region ($SEED_ID)"
"$HERE/build-region-file.sh" "$SEED_ID"

# Find the gz the region builder just wrote (it encodes the schema version in the name).
SRC="$(ls -t "$DIST/$SEED_ID"-v*.sqlite.gz | head -1)"
if [[ -z "$SRC" || ! -f "$SRC" ]]; then
  echo "!! no dist/$SEED_ID-v*.sqlite.gz produced" >&2
  exit 1
fi

DEST="$APP_ASSETS/pois-germany.sqlite.gz"
cp "$SRC" "$DEST"
echo "==> Seed asset: $DEST ($(wc -c < "$DEST" | tr -d ' ') bytes) from $(basename "$SRC")"
