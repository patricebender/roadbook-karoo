# Roadbook for Karoo

A [Hammerhead Karoo](https://www.hammerhead.io/) extension that turns a loaded route
into an offline guide of POIs along the way — coffee, food, water, bike shops, fuel —
so you can plan refuels and stops without cellular signal.

Inspired by [waybook-karoo](https://github.com/jakubfoglar/waybook-karoo), with two
additions:

- **Configurable detour distance** — how far off the route to search for POIs.
- **Category toggles** — switch POI categories on/off when building the roadbook.

> Status: early development. Milestone 1 (extension registers, reads the route, draws a
> map pin) is building; see [FOUNDATION.md](FOUNDATION.md) for the full plan.

## How it works

Two parts:

- **`extension/`** — the on-device Karoo app (Kotlin, [karoo-ext](https://github.com/hammerheadnav/karoo-ext) SDK).
  Reads the loaded route, calls the backend once to build the roadbook, caches it, and
  renders POIs as map pins and lists. Networking happens at build time so riding is
  offline and battery-friendly.
- **`backend/`** — a stateless service (TypeScript + Fastify) that takes a route polyline
  plus config and queries [OpenStreetMap](https://www.openstreetmap.org/) via the
  [Overpass API](https://overpass-api.de/), returning compact POI JSON. Deploys to
  Google Cloud Run.

## Installing on a Karoo 3

Extensions are sideloaded. Grab the latest `app-debug.apk` from
[Releases](../../releases) and install it via the Hammerhead Companion app (paste the
release APK URL), or over USB with `adb install`.

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
npm run dev        # local server on :8080
```

## Data & attribution

POI data from OpenStreetMap contributors, © OpenStreetMap contributors, available under
the [Open Database License](https://www.openstreetmap.org/copyright).

## License

[Apache-2.0](LICENSE).
