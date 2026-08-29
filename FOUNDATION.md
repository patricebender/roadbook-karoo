# Roadbook for Karoo 3 — Foundation

A Karoo 3 extension that turns a loaded route into an offline guide of POIs along the way
(coffee, food, water, bike shops, fuel), so a rider can plan refuels and stops without
cellular signal. Inspired by [waybook-karoo](https://github.com/jakubfoglar/waybook-karoo)
(APK-only, no source), with two additions:

- **Configurable detour distance** — how far off the route to search for POIs.
- **Category toggles** — switch POI categories on/off before building.

This document describes the architecture and design as they stand. Some earlier design
rationale (rejected data sources, an abandoned backend) is kept at the end for context.

## Product goals

1. **Reliable, fast builds.** Tapping "Build" returns real POIs in a second or two. The
   query runs against a database on the device, so there are no hangs, rate limits, or
   third-party query servers in the path.
2. **Ride offline.** POI data ships in the app. Building and riding need no network at all;
   the only network calls are optional per-POI enrichments a rider can choose to trigger.
3. **Glanceable UX.** This is a bike computer used at a glance, often mid-ride with gloves.
   Every async action shows visible state; controls disable while busy; success and failure
   are unambiguous. See UX Principles.

## Architecture

One part: the **`extension/`** Kotlin/Android app. Everything runs on the device — there is
no backend and no live third-party query dependency at build time.

```
┌─────────────────────────────────────────────────────────────┐
│  Karoo 3 extension (Kotlin, io.roadbook.karoo)               │
│                                                              │
│  MainActivity (Compose)          RoadbookExtension           │
│   Waybook / Filter / Detail       (map-layer service)        │
│        │                            │                        │
│        │  onBuild()                 │  onBonusAction("build") │
│        ▼                            ▼                        │
│              BuildController.runBuild()  ── mutex ──          │
│        reads route polyline / location, then                 │
│                     PoiQuery                                 │
│           (R*Tree corridor / nearby query)                   │
│                        │                                     │
│                        ▼                                     │
│            pois.sqlite  (poi + poi_rtree)                    │
│              seeded from bundled asset                       │
│                        │                                     │
│                        ▼                                     │
│              RoadbookRepository (StateFlow)                  │
│        POIs + BuildState + offline JSON cache               │
│           │                          │                       │
│           ▼                          ▼                       │
│   Compose screens          startMap → ShowSymbols on map     │
└─────────────────────────────────────────────────────────────┘
```

Both build triggers — the in-app "Build" button and the in-ride `BonusAction` — call the
same `BuildController.runBuild()`, serialized by a process-wide mutex so overlapping
triggers can't race. The controller resolves the route (or, with no route, the current
location), runs the spatial query, and publishes the result through `RoadbookRepository`.
The map layer (`RoadbookExtension.startMap`) and the Compose screens both observe that
repository, so a build updates the map pins and the list in lockstep.

### On-device POI database

POIs live in a spatial SQLite database:

- Schema: a `poi` table (`osm_id`, `lat`, `lng`, `type`, `category`, `name`, `tags` JSON)
  plus a `poi_rtree` R*Tree virtual table for the spatial index. Uses requery's bundled
  SQLite, which guarantees the R*Tree module (Android's built-in SQLite may omit it).
- The database is built offline (see Data) and shipped as an app asset
  (`pois-baden-wuerttemberg.sqlite`). On first run the app copies the asset into its files
  dir. When the bundled asset's version (`BUNDLED_DB_VERSION` in `data/PoiDatabase.kt`,
  matched to the DB's `PRAGMA user_version`) is newer than the installed copy, the app
  re-seeds — so schema/tag changes reach existing installs. A stale copy is a rebuildable
  read-only cache, safe to overwrite.

### Spatial query (`data/PoiQuery.kt`)

- **Corridor (route loaded):** compute the route bounding box, R*Tree range-scan for
  candidates in that box (filtered by enabled category), then an exact
  point-to-segment refine (`util/Polyline.kt distanceToRoute`) against the route line to
  get each POI's true detour distance and distance-along-route. One query for all
  categories, milliseconds, no approximation.
- **Nearby (no route):** a bounding-box scan around the current location refined by
  haversine distance. This is the fallback when nothing is being navigated.
- **Adaptive density.** Corridor results are bucketed into fixed 2 km along-route segments.
  Each segment keeps at most 12 POIs (nearest to the route line) so a city can't flood the
  map; sparse segments (fewer than 6 within the base radius) may reach up to 2× the radius
  (capped at 5 km) to surface isolated rural POIs. Tunables are in the `PoiQuery` companion.

### Detour radius and categories

- **Detour radius:** 500 m to 5000 m in 500 m steps, default 500 m (`data/RoadbookConfig.kt`).
- **Categories:** Restaurants, Supermarkets, Café & Bar, Water, Toilets, Bike shops, Fuel
  stations, Ice Cream. Default enabled: **Water + Bike**. Each category maps to a set of OSM
  tags and to a `Symbol.POI` type for the map pin (table below).

Config is persisted with Jetpack DataStore (`data/ConfigStore.kt`).

## UX Principles

This is a glanceable device, often used mid-ride with gloves. The UI must always answer
"what is happening and what can I do?" without the user guessing.

- **Every async action has visible state.** A build is a state machine —
  `Idle → Building → Success(count, per-category breakdown, timestamp) | Error(message)` —
  and the UI is a function of that state. Source of truth: `BuildState` in
  `RoadbookRepository` (a `StateFlow` the UI collects).
- **Controls disable while busy.** Build and config inputs disable during a build; the
  process-wide mutex in `BuildController` prevents concurrent builds regardless of trigger.
- **Confirm success glanceably.** Success shows the POI count with a relative timestamp and
  a per-category breakdown; the button becomes "Rebuild". Errors are shown in red with a
  clear message (e.g. "No POIs here — download this region?" when the route leaves the
  installed coverage).
- **Fail loud, never hang.** Route/location reads are bounded by a timeout; a stuck read
  surfaces as an Error state, not a frozen screen.
- **Two triggers, one flow.** The in-app "Build" button and the in-ride `BonusAction` both
  call `BuildController.runBuild`; a `SystemNotification` gives glanceless feedback in the
  ride view.
- **Offline is invisible.** Built POIs are cached to JSON and redraw on the map instantly on
  next launch with no network. Navigating away from a route clears the roadbook.

## Screens (Compose, `MainActivity` + `ui/`)

No navigation framework — a small `sealed interface Screen` the host switches on, so list
scroll state is preserved across navigation.

- **Waybook** (`ui/WaybookScreen.kt`) — the route view: a header with build/clear/filter
  shortcuts and a live build-status line, a route distance strip with POI dots
  (`ui/RouteStrip.kt`), and a scrollable list of POIs along the route ordered by
  distance-along-route. Rows show an open/closed badge when hours are known.
- **Filter** (`ui/FilterScreen.kt`) — build settings: the Build action in the top bar
  (always visible) with build status below it, the detour-radius slider, category toggles,
  and Clear at the bottom.
- **Detail** (`ui/PoiDetailScreen.kt`) — a centered hero (category disc, name, type) then
  grouped cards: open/closed status pill + route context (distance-along / detour), the
  weekday opening-hours table, contact (address + phone), a scannable website QR, and an
  on-demand description. Data is from the DB tags; hours and description have optional
  online fallbacks (below).

## Optional online enrichment

All POI data is offline. Two per-POI enrichments make on-demand network calls, routed
through the **Karoo HTTP bridge** (so they work over the paired phone, not just WiFi):

- **Wikipedia description** (`data/WikipediaClient.kt`) — fetched when a POI has a
  `wikipedia`/`wikidata` tag, cached in memory for the session. Falls back to the OSM
  `description` tag when there's no Wikipedia link.
- **Google Places opening hours** (`data/PlacesClient.kt`) — offered only when OSM has no
  `opening_hours`, the category is one where hours matter (Supermarkets, Café & Bar,
  Restaurants, Fuel, Ice Cream), and a `PLACES_API_KEY` is configured at build time. The
  resolved **Place ID** is persisted (Maps ToS permits caching Place IDs indefinitely); the
  **hours themselves are never persisted** — kept in memory with a short TTL and re-fetched,
  per Maps ToS. Opening-hours parsing of the OSM `opening_hours` string lives in
  `data/OpeningHours.kt` (a pragmatic subset: weekday table, 24/7, "opens at", seasonal
  fallback).

## Data

POI data is from **OpenStreetMap**, pre-extracted into the bundled SQLite. No live query
service (no Overpass, no Places at build time).

The database is built by **`extension/tools/poi-db/`** (TypeScript + `osmium`): download a
regional Geofabrik extract → `osmium tags-filter` to just our POI tags → export GeoJSONSeq
→ load the `poi` table + R*Tree with an allowlisted set of tags (`opening_hours`, `website`,
`phone`, `addr:*`, `wikipedia`, …). Coverage currently ships **Baden-Württemberg**; widening
is an `OSM_REGION` change (see `extension/tools/poi-db/README.md`).

### Categories → OSM tags → Symbol.POI type

| Category         | OSM tags                                     | Symbol.POI type            |
|------------------|----------------------------------------------|----------------------------|
| Restaurants      | `amenity=restaurant/fast_food`               | FOOD                       |
| Supermarkets     | `shop=supermarket/convenience`               | CONVENIENCE_STORE          |
| Café & Bar       | `amenity=cafe` / `amenity=bar/pub`           | COFFEE / BAR               |
| Water            | `amenity=drinking_water`                     | REST_STOP → WATER icon     |
| Toilets          | `amenity=toilets`                            | RESTROOM                   |
| Bike shops       | `shop=bicycle`                               | BIKE_SHOP                  |
| Fuel stations    | `amenity=fuel` (car/repair/automat excluded) | GAS_STATION                |
| Ice Cream        | (declared; not yet in the DB pipeline)       | —                          |

Fuel is included for its on-site shop (drinks/snacks), not the fuel; car dealerships,
repair shops, and unattended automats are filtered out. `Ice Cream` exists in the app's
`Category` enum but has no tag rule in the pipeline yet, so it produces no POIs until the DB
is rebuilt with an `amenity=ice_cream` rule.

The category set must stay in sync across three places: the Kotlin `Category` enum
(`data/RoadbookConfig.kt`), the pipeline `categories.ts`/`contract.ts`, and the karoo-ext
`Symbol.POI.Types`.

## karoo-ext facts we rely on (verified in source)

- `OnNavigationState → NavigatingRoute.routePolyline` (Google encoded polyline, precision 5).
- `MakeHttpRequest` / `OnHttpResponse.Complete` — HTTP via the Karoo (WiFi or paired phone),
  used only for the optional enrichments.
- `ShowSymbols` / `HideSymbols` + `Symbol.POI(type=…)` — map effects; a POI type string that
  doesn't match a `Symbol.POI.Types` constant renders the generic pin.
- `BonusAction` (declared in `extension_info.xml`) + `onBonusAction(actionId)` — in-ride
  trigger.
- **`startMap` fires only while navigating a route** — it is not a settings toggle. Pins
  appear once a route is being navigated; leaving the route clears them.

## Releasing

The extension is versioned with semantic versioning driven by
[release-please](https://github.com/googleapis/release-please) and Conventional Commits.
`feat:`/`fix:` commits touching `extension/**` on `main` keep a Release PR updated (bumping
`versionName` in `extension/app/build.gradle.kts` and `extension/CHANGELOG.md`); merging that
PR cuts the release — tag `extension-vX.Y.Z`, GitHub Release, and a CI-built APK attached as
a release asset. Normal pushes do not publish an APK. Full detail in
[docs/releasing.md](docs/releasing.md).

## Dev loop & distribution

- **Fast loop:** USB + `adb`, `./gradlew :app:installDebug` onto the real Karoo
  (Android 12 / API 32). `startMap` activates once you navigate a route; use a continuous
  `adb logcat` capture — timed captures miss the trigger.
- **Distribution:** install the release APK by pasting its URL into the Hammerhead Companion
  app.

## Repo layout

```
roadbook/
  FOUNDATION.md            # this doc
  README.md
  docs/releasing.md
  extension/               # Kotlin Android app (karoo-ext)
    app/src/main/kotlin/io/roadbook/karoo/
      data/                # config, repository, POI DB + spatial query, hours, clients
      build/               # BuildController + BuildState
      extension/           # RoadbookExtension (map layer + BonusAction)
      ui/                  # Compose screens (Waybook, Filter, Detail, RouteStrip)
      util/                # polyline decode + geometry
    app/src/main/assets/   # bundled pois-*.sqlite
    tools/poi-db/          # OSM → SQLite pipeline (TypeScript + osmium)
  .github/workflows/       # release-please + APK build
```

## Open items (deferred)

- Distance-to-next-POI data field; category-colored pin styling.
- Multiple-region install/merge (the `PoiDatabase` KDoc references a `RegionInstaller` that
  isn't built yet — coverage is single-region seeding for now).
- Widen coverage beyond Baden-Württemberg; automate the extract refresh.
- Ice Cream category: add the `amenity=ice_cream` rule and rebuild the DB.

---

## Appendix: earlier design decisions (superseded)

For context — none of this is in the current app.

**Data source.** Google Maps was ruled out (Places ToS forbids the offline caching a
roadbook needs; Nearby Search is per-point priced, which multiplies badly over a route).
Public **Overpass** was tried and rejected: it rate-limited us (`200 → 429 → 504`), making
builds slow (~60 s) and unreliable. The query logic was fine; the shared server was the
problem. The pre-extracted bundled SQLite replaced it.

**Backend.** An earlier design put the SQLite behind a TypeScript + Fastify service on Cloud
Run (scale-to-zero), with a chunked HTTP protocol (`/build/start` + paged `/build/:id/page/:n`)
to work around the Karoo HTTP bridge's 100 KB response cap. That backend was removed: the
database is small enough to bundle and query in-process, which is faster, fully offline, and
removes the deploy/cost/latency surface entirely. The data pipeline moved from
`backend/data-pipeline/` to `extension/tools/poi-db/`.
