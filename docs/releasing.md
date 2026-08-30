# Releasing the extension

The extension is versioned with **semantic versioning** driven by
[release-please](https://github.com/googleapis/release-please) and
[Conventional Commits](https://www.conventionalcommits.org/).

## How a release happens

1. Commit to `main` using Conventional Commit prefixes:
   - `fix: ...` → patch bump (0.1.0 → 0.1.1)
   - `feat: ...` → minor bump (0.1.0 → 0.2.0)
   - `feat!: ...` or a `BREAKING CHANGE:` footer → major bump (0.1.0 → 1.0.0)
   - `chore:` / `docs:` / `refactor:` / `test:` / `ci:` → no version bump
   Only commits touching `extension/**` count toward the extension version.
2. release-please opens/updates a **Release PR** that bumps `versionName` in
   `extension/app/build.gradle.kts` and updates `extension/CHANGELOG.md`. It keeps updating
   as you push more commits.
3. **Merge the Release PR** when you want to cut a release. That:
   - creates tag `extension-vX.Y.Z` and a GitHub Release, then
   - builds the APK (`versionName` = the released version, `versionCode` = CI run number)
     and attaches `roadbook-extension-vX.Y.Z.apk` to the release.
4. Install: paste the release APK URL into the Hammerhead Companion app.

## Notes

- Normal pushes do **not** publish an APK — only merging the Release PR does.
- `versionCode` is the CI run number: monotonic (Android requires it to increase) but not
  tied to the semver number. That's fine and intentional.
- The version of record is the annotated `versionName` line in `build.gradle.kts`. The
  annotation is a **trailing comment on the same line**:
  `versionName = "X.Y.Z" // x-release-please-version`. release-please replaces the
  semver string on that exact line — don't move the comment to its own line or reformat it.
- The bundled POI database is not versioned this way — it's rebuilt on demand via
  `extension/tools/poi-db/`, and its own `PRAGMA user_version` drives app re-seeding.

## Releasing downloadable region files

Separate from the APK release. The in-app region picker downloads POI files (Germany
Complete + each Bundesland, plus countries) from a **dedicated GitHub Release**, not the
APK release. These are large binaries rebuilt from OSM on demand, so they get their own
tag and are re-uploaded only when coverage or the DB schema changes — not every app
release.

### Preferred: the `Build region files` workflow

Full coverage downloads many GB of OSM extracts, so build on the runner's bandwidth, not
a laptop. Trigger **Actions → Build region files** (`.github/workflows/regions.yml`) with:

- `regions` — a space-separated list of region ids (see `tools/poi-db/regions.ts ids`), or
  `all` for the whole catalog. Default is a small smoke-test set.
- `tag` — the release tag, default `regions-latest` (what the app points at).

The workflow is **cumulative**: it downloads the release's current `manifest.json` first,
so building a subset **adds/refreshes only those regions** and keeps the rest. New files
are uploaded, rebuilt ones overwrite their asset, untouched ones stay — the release
accumulates coverage across runs instead of last-run-wins. Build the whole catalog by
running once with `all`, or grow it region by region.

The manifest's `schemaVersion` **must equal** the app's `BUNDLED_DB_VERSION`; a mismatch
makes the app refuse the download and prompt the rider to update. Bumping the DB version
invalidates every published file: the merge discards prior entries whose schema differs,
so **rebuild all regions** (`all`) after a bump rather than a subset (see the poi-db
README).

### Manual (local) build

Possible but not the happy path — mind the cumulative caveat below.

```bash
cd extension/tools/poi-db && npm install
npm run build:regions            # → dist/*.sqlite.gz + dist/manifest.json
gh release create regions-latest dist/* --title "POI regions" --notes "…" \
  || gh release upload regions-latest dist/* --clobber
```

Commit the refreshed `app/src/main/assets/regions.json` (the offline picker catalog).

**Cumulative caveat:** `build-manifest.ts` merges onto a `dist/manifest.json` if one is
present, so to add a subset without dropping the rest, pull the published manifest into
`dist/` **before** building:

```bash
mkdir -p dist && gh release download regions-latest --pattern manifest.json --dir dist
REGIONS_IDS="germany-bayern" npm run build:regions
gh release upload regions-latest dist/* --clobber
```

Skip that pre-download and a subset build writes a manifest listing only what you built,
so the app treats every other region as unavailable. Building `all` needs no pre-download.

If you version the tag instead (e.g. `regions-v2`), rebuild the manifest with
`REGIONS_BASE_URL=".../releases/download/regions-v2/" npm run build:manifest` so its
`baseUrl` matches, and update `MANIFEST_URL` in the app — the stable `regions-latest` tag
avoids that code change.

Automated Geofabrik refresh (rebuilding region files on a schedule as OSM data ages) is
a known follow-up, not wired yet.
