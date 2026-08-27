# Roadbook for Karoo

A [Hammerhead Karoo](https://www.hammerhead.io/) extension that turns a loaded route
into an offline guide of POIs along the way — coffee, food, water, bike shops, fuel —
so you can plan refuels and stops without cellular signal.

Inspired by [waybook-karoo](https://github.com/jakubfoglar/waybook-karoo), with two
additions:

- **Configurable detour distance** — how far off the route to search for POIs.
- **Category toggles** — switch POI categories on/off when building the roadbook.

> Status: working on-device — the extension reads the route, you set a detour radius and
> categories, tap Build, and typed POI pins appear on the map. Data source is moving from
> live Overpass to a pre-extracted spatial database for speed/reliability. See
> [FOUNDATION.md](FOUNDATION.md) for architecture, goals, and UX principles.

## How it works

Two parts:

- **`extension/`** — the on-device Karoo app (Kotlin, [karoo-ext](https://github.com/hammerheadnav/karoo-ext) SDK).
  Reads the loaded route, lets you configure the build (detour radius + category toggles),
  calls the backend at build time, and renders POIs as typed map pins. Results are cached
  so riding is fully offline. The build is a state machine (Building → Success/Error) that
  drives all UI feedback — spinner, live "N/total" progress, glanceable result.
- **`backend/`** — a service (TypeScript + Fastify) on Google Cloud Run. POIs come from
  [OpenStreetMap](https://www.openstreetmap.org/), **pre-extracted into a spatial SQLite
  database baked into the image** (no live Overpass dependency). A build is delivered as a
  chunked protocol (`/build/start` returns a count, then `≤100 KB` pages) so long routes
  work within the Karoo's HTTP size cap with no data loss.

## Installing on a Karoo 3

Extensions are sideloaded. Grab the latest APK from [Releases](../../releases) and install
it via the Hammerhead Companion app (paste the release APK URL). For development, use USB:
`adb install` or `./gradlew :app:installDebug`.

## Building

### Extension
Requires JDK 17 and the Android SDK. karoo-ext is on GitHub Packages (auth required even
though it's public) — put credentials in `~/.gradle/gradle.properties`:

```properties
gpr.user=<your-github-username>
gpr.key=<a-PAT-with-read:packages>
```

```sh
cd extension
./gradlew :app:assembleDebug
```

### Backend
```sh
cd backend
npm install

# One-time: build the POI database from an OSM extract (needs osmium-tool).
# Downloads a regional extract, filters to our POI tags, loads a spatial SQLite.
OSM_REGION=europe/germany/baden-wuerttemberg npm run build:poi-db

npm run dev        # local server on :8080 — queries pois.sqlite, fully offline
```

Releases are cut with [release-please](https://github.com/googleapis/release-please):
merge the release PR → tag `extension-vX.Y.Z` → CI builds and attaches the APK.
See [docs/releasing.md](docs/releasing.md).

## Data & attribution

POI data from OpenStreetMap contributors, © OpenStreetMap contributors, available under
the [Open Database License](https://www.openstreetmap.org/copyright).

## License

[Apache-2.0](LICENSE).
