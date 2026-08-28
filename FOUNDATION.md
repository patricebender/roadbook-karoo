# Roadbook for Karoo 3 — Foundation

> **Note (architecture superseded):** the app is now **fully on-device** — there is no
> backend. The extension queries the bundled spatial SQLite directly (`data/PoiQuery`), so
> the Cloud Run service, the chunked `/build/*` HTTP protocol, and `BackendClient` described
> below have been removed. The POI database is built offline by `extension/tools/poi-db/`
> (formerly `backend/data-pipeline/`). Opening hours come from OSM tags, with an optional
> on-demand Google Places lookup. The rest of this document is kept for design rationale.

A Karoo 3 extension that turns a loaded route into an offline guide of POIs along the way
(coffee, food, water, bike shops, fuel), so a rider can plan refuels and stops without
cellular signal. Inspired by [waybook-karoo](https://github.com/jakubfoglar/waybook-karoo)
(APK-only, no source), with two additions:

- **Configurable detour distance** — how far off the route to search for POIs.
- **Category toggles** — switch POI categories on/off before building.

## Product goals

1. **Reliable, fast builds.** Tapping "Build" returns real POIs in a second or two — no
   hangs, no rate limits, no dependence on flaky third-party query servers.
2. **Ride offline.** Build once (on WiFi at home or on the device SIM), cache everything,
   then ride for hours with pins on the map and no network.
3. **Excellent UX (a first-class goal, not polish).** See the UX Principles section — this
   is a bike computer used at a glance, often mid-ride. Feedback, state, and control flow
   matter as much as the data.
4. **Cheap to run.** Scale-to-zero backend, no always-on database, well within free tiers.

## Architecture

Two parts. The device part *must* be native Android/Kotlin (no Node runs on the Karoo).
Node/GCP is the backend only. POI data is **pre-extracted from OpenStreetMap into a spatial
SQLite database baked into the backend image** — there is no live third-party query
dependency at request time.

```
┌───────────────────────────────┐   POST /build/start          ┌───────────────────────────┐
│  Karoo 3 extension (Kotlin)    │   {polyline,detour,cats}     │  Backend (TS + Fastify)   │
│  - karoo-ext SDK               │ ───────────────────────────▶ │  on Cloud Run (scale→0)   │
│  - config screen (slider,      │   ← {buildId,totalCount,     │                           │
│    category switches)          │      pageCount}              │  pois.sqlite (baked in):  │
│  - Build button + BonusAction  │                              │   poi + R*Tree index      │
│  - BuildState machine drives   │   GET /build/:id/page/:n     │  queryCorridor():         │
│    all UI (spinner, counts)    │ ───────────────────────────▶ │   R*Tree bbox scan +      │
│  - draws typed pins on the map │   ← {page, pois[]} (<100K)   │   point-to-segment refine │
│  - caches POIs offline (JSON)  │                              │  (one query, ms, exact)   │
└───────────────────────────────┘                              └───────────────────────────┘
        │ observes RoadbookRepository (StateFlow) → ShowSymbols on the native map
```

### Why chunked (start + pages)?
The Karoo HTTP bridge caps a single response at **100 KB** (`MAX_REQUEST_SIZE = 100_000`).
There is **no streaming** on the SDK — only buffered request/response. So a build is
delivered as: a tiny `start` call that returns the POI count, then paged fetches (≤100 KB
each) appended progressively. This supports arbitrarily long routes (100–300 km) with **no
data loss**, and gives the UI a count to show immediately and progress as pages arrive.

### Battery model (design constraint)
- **All networking happens at Build time.** During the ride the extension does no
  networking; it draws cached pins and reacts only to Karoo's existing `OnLocationChanged`
  (no extra GPS consumer).
- The nearby fallback (`/nearby/start`) is the one explicit on-demand exception.

## UX Principles (first-class)

This is a glanceable device, often used mid-ride with gloves. The UI must always answer
"what is happening and what can I do?" without the user guessing.

- **Every async action has visible state.** No silent work. A build is a state machine —
  `Idle → Building(total?, loaded) → Success(count, time) | Error(msg)` — and the UI is a
  pure function of that state. Source of truth: `BuildState` in `RoadbookRepository`
  (a `StateFlow` the UI collects).
- **Progress, not just spinners.** "Loading 120 / 247 POIs…" beats an indeterminate spinner
  — the count comes from the `start` call, updates per page.
- **Controls disable while busy.** The Build button and config inputs are disabled during a
  build, so a rider can't fire concurrent/duplicate builds (a mutex also guards this
  server-agnostically in `BuildController`).
- **Confirm success glanceably.** "88 POIs · just now" with a relative timestamp; the button
  becomes "Rebuild". Errors are red with a clear message and a retry path.
- **Fail loud, never hang.** Every network call has a timeout; a stuck request surfaces as an
  Error state, not a frozen screen. Requests use `waitForConnection = false` so the Karoo
  never silently queues them.
- **Two triggers, one flow.** In-app "Build now" button and an in-ride `BonusAction` both
  call the same `BuildController.runBuild`, with an `InRideAlert`/`SystemNotification` for
  glanceless feedback in the ride view.
- **Offline is invisible.** Cached POIs redraw instantly on the map with no network; the user
  shouldn't be able to tell a rebuild from a cache load except for freshness.

Future UX to keep in mind: a Route/Nearby list tab (distance-ahead ordering), a data field
(distance-to-next-POI), "open now" evaluation from `opening_hours`, and category-colored
pin styling.

## Data

**OpenStreetMap, pre-extracted — no Overpass at request time.**
- Google Maps is **out**: Places ToS forbids offline caching (the whole point of a
  roadbook), and Nearby Search is per-point priced (~$32/1k, 5k free/mo) which multiplies
  badly over route corridors.
- Public **Overpass** was tried and rejected: it rate-limits us (`200 → 429 → 504`), making
  builds unreliable/slow (~60 s). Our query logic was fine; the shared server was the problem.
- **Solution:** a pipeline (`backend/data-pipeline/`) downloads a regional OSM extract,
  `osmium tags-filter`s to just our ~12 POI tags, and loads a compact **spatial SQLite**
  (`poi` + R*Tree). Baked into the container → in-process queries in milliseconds, no rate
  limits, cache-able forever (ODbL allows it).
- Coverage starts with **Baden-Württemberg**; scaling to Germany/DACH is a one-line build
  change. Wikipedia/Wikivoyage stories + Wikimedia photos are deferred enrichment.

### Categories → OSM tags → Symbol.POI type
| Toggle              | OSM tags                                                    | Symbol.POI type                   |
|---------------------|-------------------------------------------------------------|-----------------------------------|
| Food & drink        | `amenity=cafe/restaurant/fast_food/bar/pub`, `shop=convenience/supermarket` | COFFEE/FOOD/BAR/CONVENIENCE_STORE |
| Water & restrooms   | `amenity=drinking_water/toilets`                            | REST_STOP/RESTROOM                |
| Bike                | `shop=bicycle`, `amenity=bicycle_parking`                   | BIKE_SHOP/BIKE_PARKING            |
| Fuel                | `amenity=fuel`                                              | GAS_STATION                       |

## Backend

- **TypeScript + Fastify** on Cloud Run (scale-to-zero). No external DB, no Overpass.
- **Endpoints** (chunked protocol):
  - `POST /build/start` `{polyline, detourMeters, categories[]}` → `{buildId, totalCount,
    pageSize, pageCount}`
  - `POST /nearby/start` `{lat, lng, radiusMeters, categories[]}` → same handle
  - `GET  /build/:id/page/:n` → `{page, pois[]}` (trimmed POIs, ≤100 KB)
- **Build store**: computed POIs held in-memory under `buildId` with a TTL, so pages don't
  recompute. Lost only if the instance recycles mid-build (rare; client re-issues `start`).
- **Spatial query** (`poiStore.ts`): route bbox → R*Tree candidate scan → exact
  point-to-segment refine against the route line (`polyline.ts distanceToRoute`). One query,
  all categories, no anchor-gap approximation.

### Page POI shape (what the device gets)
```jsonc
{ "page": 0, "pois": [
  { "id":"osm:n123", "lat":47.12, "lng":8.56, "type":"COFFEE",
    "name":"Café Example", "distancesAlongRoute":[12500] }
]}
```
Heavy tags (opening_hours/website/…) are kept server-side and omitted from pages to stay
under the size cap; re-added when a POI detail view needs them.

## Extension (Kotlin, packages under `io.roadbook.karoo`)

- `data/` — `RoadbookConfig` + `Category`, `ConfigStore` (DataStore), `Poi`,
  `RoadbookRepository` (POI `StateFlow` + `BuildState` + offline JSON cache).
- `network/BackendClient` — `startBuild`/`startNearby`/`fetchPage` over
  `MakeHttpRequest`/`OnHttpResponse`, `waitForConnection=false` + per-call timeouts.
- `build/BuildController` + `BuildState` — orchestrates start → paged appends → progress,
  serialized by a mutex; the shared build flow for both triggers.
- `extension/RoadbookExtension` — the map-layer service: `onBonusAction("build")` triggers a
  build; `startMap` observes the repository and draws typed pins reactively.
- `MainActivity` — Compose config screen (radius slider, category switches) + state-driven
  Build button.

### karoo-ext facts we rely on (verified in source)
- `OnNavigationState → NavigatingRoute.routePolyline` (Google encoded, precision 5).
- `MakeHttpRequest`/`OnHttpResponse.Complete` — HTTP via the Karoo (WiFi/SIM), ≤100 KB.
- `ShowSymbols`/`HideSymbols` + `Symbol.POI(type=…)` — top-level `MapEffect` subclasses.
- `BonusAction` (in `extension_info.xml`) + `onBonusAction(actionId)` — in-ride trigger.
- **`startMap` fires only while navigating a route** — not a settings toggle.

## Dev loop & distribution

- **Fast loop:** USB + `adb`, `./gradlew :app:installDebug` (~15 s) onto the real Karoo
  (Android 12 / API 32). `startMap` activates once you navigate a route; use a continuous
  `adb logcat` capture (timed captures miss the trigger).
- **Backend local loop:** with `pois.sqlite` built, the whole backend runs offline on the
  laptop — queries in ms, no deploy needed. `npm run dev`.
- **Distribution:** release-please (Conventional Commits) → tag `extension-vX.Y.Z` → CI
  builds the APK → GitHub release → paste the APK URL into the Hammerhead Companion app.

## Repo layout (monorepo)
```
roadbook/
  FOUNDATION.md            # this doc
  README.md
  docs/releasing.md
  extension/               # Kotlin Android app (karoo-ext)
  backend/
    src/                   # Fastify service + poiStore + chunked protocol
    data-pipeline/         # osmium → SQLite POI extraction
    deploy/                # GCP setup + Cloud Run deploy scripts
  .github/workflows/       # deploy-backend, release-please
```

## Status (see memory for current detail)
- Milestones 1–3 done and verified on-device (registration, route read, map pins,
  user-triggered build, config, chunked protocol, build-state UX).
- **In progress:** replacing Overpass with the pre-extracted SQLite store (this doc's Data
  section) — the reliability/speed foundation.

## Open items (deferred)
- Route/Nearby list tab + data field; "open now" from `opening_hours`; pin styling by type.
- Widen coverage beyond Baden-Württemberg; automate the extract refresh.
- Wikipedia/Wikivoyage + Wikimedia enrichment; update checker.
