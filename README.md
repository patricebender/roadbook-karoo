# Roadbook for Karoo

A [Hammerhead Karoo](https://www.hammerhead.io/) extension that turns a loaded route into an
offline guide of POIs along the way — coffee, food, water, bike shops, fuel — so you can plan
refuels and stops without cellular signal.

Two things set it apart: a **configurable detour distance** and **category toggles**.

## Features

- **Offline by default.** POIs ship with the app in a spatial SQLite database. Building the
  roadbook and riding with it need no network — no query server, no rate limits.
- **Route corridor search.** Loads the route you're navigating and finds POIs within your
  chosen detour distance of it, each shown with its distance along the route and how far off
  the route it is. With no route loaded, it falls back to POIs near your current location.
- **Configurable detour distance.** 500 m to 5 km, in 500 m steps.
- **Category toggles.** Restaurants, Supermarkets, Café & Bar, Water, Toilets, Bike shops,
  Fuel. Water and Bike are on by default.
- **Adaptive density.** Keeps rural POIs visible while capping dense areas, so a city doesn't
  flood the map and a quiet stretch still shows what's there.
- **Two ways to build.** An in-app Build button and an in-ride `BonusAction`, so you can
  rebuild without leaving the ride view. Both show a live status (searching → count, or a
  clear error) and a per-category breakdown on success.
- **Typed map pins + a Waybook list.** POIs draw as typed pins on the native map and appear
  in a scrollable list ordered by distance along the route.
- **Place detail.** Per-POI screen with opening hours (a weekday table with open/closed
  status), address and phone, a scannable website QR, and a short description.
- **Opening hours from OSM,** parsed from the `opening_hours` tag (weekday table, 24/7,
  "opens at", seasonal). For food/fuel POIs where OSM has none, an optional on-demand Google
  Places lookup fills the gap (requires an API key at build time).
- **Optional descriptions** from Wikipedia, fetched on demand for POIs that link to it.

The only network calls are the optional, on-demand opening-hours and description lookups.
They route device→provider through the Karoo HTTP bridge, so they work over a paired phone,
not just WiFi.

## How it works

The **`extension/`** directory is the whole app — an on-device Karoo app (Kotlin,
[karoo-ext](https://github.com/hammerheadnav/karoo-ext) SDK). It reads the loaded route,
lets you set a detour radius and category toggles, and queries the **spatial SQLite database
bundled with the app** (an R*Tree corridor search refined against the route line) to find
POIs — no server, fully offline. Results render as typed map pins and in the Waybook list.

POI data comes from [OpenStreetMap](https://www.openstreetmap.org/). The bundled database is
generated offline by **`extension/tools/poi-db/`** (downloads a regional extract, filters to
our POI tags, loads a spatial SQLite) and baked into the app as an asset the extension seeds
on first run. Coverage currently ships Baden-Württemberg.

See [FOUNDATION.md](FOUNDATION.md) for the full architecture and design.

## Installing on a Karoo 3

Extensions are sideloaded. Grab the latest APK from [Releases](../../releases) and install it
via the Hammerhead Companion app (paste the release APK URL). For development, use USB:
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

The Google Places hours fallback needs a `PLACES_API_KEY` at build time; without it, that
feature is simply hidden.

### Refreshing the POI database

The bundled POI database is built offline (needs
[osmium-tool](https://osmcode.org/osmium-tool/)):

```sh
cd extension/tools/poi-db
npm install
# Downloads a regional extract, filters to our POI tags, writes the bundled asset.
OSM_REGION=europe/germany/baden-wuerttemberg npm run build:poi-db
```

See [`extension/tools/poi-db/README.md`](extension/tools/poi-db/README.md) for options
(coverage, forced re-download, bumping the DB version).

## Releases

The extension is versioned with [release-please](https://github.com/googleapis/release-please)
and Conventional Commits. `feat:`/`fix:` commits touching `extension/**` on `main` keep a
Release PR updated; merging it cuts the release — tag `extension-vX.Y.Z`, a GitHub Release,
and a CI-built APK attached as a release asset. Normal pushes don't publish an APK. See
[docs/releasing.md](docs/releasing.md).

## Data & attribution

POI data from OpenStreetMap contributors, © OpenStreetMap contributors, available under the
[Open Database License](https://www.openstreetmap.org/copyright).

## License

[Apache-2.0](LICENSE).
