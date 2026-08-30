#!/usr/bin/env bash
# Build every downloadable region file + the manifest, and refresh the app's bundled
# region catalog (assets/regions.json). This is the release-time entrypoint.
#
#   ./build-all-regions.sh                     # all regions in regions.ts
#   REGIONS_IDS="germany-bremen italy" ./build-all-regions.sh   # a subset
#
# Region files land in dist/<id>-v<schema>.sqlite.gz; manifest in dist/manifest.json.
# Upload dist/* to the GitHub release (see docs/releasing.md). regions.json is committed
# with the app so the picker renders offline.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP_ASSETS="$(cd "$HERE/../.." && pwd)/app/src/main/assets"

# 1. Regenerate the app-facing region catalog (label + group only) from regions.ts,
#    so the picker list and the pipeline can never drift.
echo "==> Generating $APP_ASSETS/regions.json"
npx --yes tsx "$HERE/regions.ts" app-json > "$APP_ASSETS/regions.json"

# 2. Build each region file.
IDS="${REGIONS_IDS:-$(npx --yes tsx "$HERE/regions.ts" ids)}"
for id in $IDS; do
  echo "==> build-region-file.sh $id"
  "$HERE/build-region-file.sh" "$id"
done

# 3. Emit the manifest over everything now in dist/.
echo "==> build-manifest.ts"
npx --yes tsx "$HERE/build-manifest.ts"

echo "==> All done. dist/ ready to upload."
