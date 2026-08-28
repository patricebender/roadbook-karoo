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

The **`extension/`** is the whole app — an on-device Karoo app (Kotlin,
[karoo-ext](https://github.com/hammerheadnav/karoo-ext) SDK). It reads the loaded route,
lets you configure the build (detour radius + category toggles), and queries a **spatial
SQLite database bundled with the app** (R*Tree corridor search) to find POIs along the
route — no server, fully offline. POIs render as typed map pins and in a scrollable
"Waybook" list with opening hours. The only network calls are optional, on-demand, and go
device→provider through the Karoo HTTP bridge: a Wikipedia blurb and a Google Places
opening-hours lookup for POIs where OpenStreetMap has none.

POI data comes from [OpenStreetMap](https://www.openstreetmap.org/). The bundled database
is generated offline by **`extension/tools/poi-db/`** (downloads a regional extract, filters
to our POI tags, loads a spatial SQLite) and baked into the app as an asset the extension
seeds on first run.

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

### Refreshing the POI database
The bundled POI database is built offline (needs [osmium-tool](https://osmcode.org/osmium-tool/)):

```sh
cd extension/tools/poi-db
npm install
# Downloads a regional extract, filters to our POI tags, writes the bundled asset.
OSM_REGION=europe/germany/baden-wuerttemberg npm run build:poi-db
```

See [`extension/tools/poi-db/README.md`](extension/tools/poi-db/README.md) for options
(coverage, forced re-download, bumping the DB version).

Releases are cut with [release-please](https://github.com/googleapis/release-please):
merge the release PR → tag `extension-vX.Y.Z` → CI builds and attaches the APK.
See [docs/releasing.md](docs/releasing.md).

## Data & attribution

POI data from OpenStreetMap contributors, © OpenStreetMap contributors, available under
the [Open Database License](https://www.openstreetmap.org/copyright).

## License

[Apache-2.0](LICENSE).
