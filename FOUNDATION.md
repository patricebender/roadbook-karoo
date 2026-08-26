# Roadbook for Karoo 3 — Foundation

A Karoo 3 extension that turns a loaded route into an offline illustrated guide of
POIs along the way. Inspired by [waybook-karoo](https://github.com/jakubfoglar/waybook-karoo)
(APK-only, no source), with two additions:

- **Configurable detour distance** — how far off the route to search for POIs.
- **Category toggles** — switch POI categories on/off when building.

## Architecture

Two parts. The device part *must* be native Android/Kotlin (no Node on the Karoo).
Node/GCP is the backend only.

```
┌─────────────────────────────┐        POST /build            ┌──────────────────────┐
│  Karoo 3 extension (Kotlin)  │  {polyline, detourMeters,     │  Node backend         │
│  - karoo-ext SDK             │   categories}                 │  (TS + Fastify)       │
│  - config screen             │ ────────────────────────────▶ │  on Cloud Run         │
│  - Build → 1 HTTP call       │                               │  - buffer polyline    │
│  - cache POIs (Room/SQLite)  │ ◀──────────────────────────── │  - Overpass query     │
│  - ride offline: pins + list │        compact POI JSON       │  - normalize → POIs   │
│  - data field                │                               │  - cache              │
│  - "fetch nearby now" (POST /nearby, on demand)              │                       │
└─────────────────────────────┘                               └──────────────────────┘
```

### Battery model (design constraint)
Network + GPS/CPU wakeups drain the device. So:
- **All networking happens once, at Build time** (default: online at home, ride offline).
- During the ride the extension does **no networking** and adds **no GPS consumer** —
  it piggybacks Karoo's existing `OnLocationChanged` stream and does cheap local
  distance-to-POI math against the cached data.
- The only per-ride network is the explicit, single-tap "fetch nearby now" fallback.

## karoo-ext SDK primitives we rely on
(verified in `hammerheadnav/karoo-ext` source)

- `OnNavigationState → NavigatingRoute` — loaded route polyline (Google encoded,
  precision 5), route distance, name, associated POIs.
- `OnLocationChanged` — live position/orientation (already computed by Karoo).
- `OnGlobalPOIs` — POIs near rider.
- `MakeHttpRequest` / `OnHttpResponse` — HTTP routed through Karoo (WiFi or device SIM).
- `MapEffect.ShowSymbols` / `ShowPolyline` + `Symbol.POI` — draw pins/lines on native map.
  POI type is a **fixed enum** (COFFEE, FOOD, BAR, CONVENIENCE_STORE, GAS_STATION,
  BIKE_SHOP, BIKE_PARKING, REST_STOP, RESTROOM, SUMMIT, MONUMENT, PARK, …).
- Custom data fields + `RemoteViews` for the Route/Nearby tab UI.

## Data
**OSM/Overpass only for MVP.**
- OSM gives structured POIs + tags (`opening_hours`, `website`, `phone`).
- Google Maps is **out**: Places ToS forbids offline caching; OSM's ODbL allows it.
- Wikipedia/Wikivoyage stories + Wikimedia photos are **deferred** — later backend-only
  enrichment, no device change needed.

### MVP categories → OSM tags → Symbol.POI type
| Toggle              | OSM tags                                            | Symbol.POI type            |
|---------------------|-----------------------------------------------------|----------------------------|
| Food & drink        | `amenity=cafe/restaurant/fast_food/bar`, `shop=convenience` | COFFEE/FOOD/BAR/CONVENIENCE_STORE |
| Water & restrooms   | `amenity=drinking_water/toilets`                    | REST_STOP/RESTROOM         |
| Bike-specific       | `shop=bicycle`, `amenity=bicycle_parking`           | BIKE_SHOP/BIKE_PARKING     |
| Fuel stations       | `amenity=fuel`                                       | GAS_STATION                |

## Backend decisions
- **TypeScript + Fastify**, stateless, Cloud Run (scales to zero).
- **Public Overpass now + aggressive caching**; self-host Overpass later if needed
  (migration path noted, not built).
- Endpoints:
  - `POST /build`  `{ polyline, detourMeters, categories[] }` → `{ pois[] }`
  - `POST /nearby` `{ lat, lng, radiusMeters, categories[] }` → `{ pois[] }`

### POI JSON contract (shared, keep in sync)
```jsonc
{
  "pois": [
    {
      "id": "osm:node:123456",
      "lat": 47.1234,
      "lng": 8.5678,
      "type": "COFFEE",              // maps to Symbol.POI.Types
      "name": "Café Example",
      "distancesAlongRoute": [12500], // meters; empty for /nearby
      "tags": { "opening_hours": "Mo-Fr 08:00-18:00", "website": "..." }
    }
  ]
}
```

## Repo layout (monorepo)
```
roadbook/
  FOUNDATION.md        # this doc
  extension/           # Kotlin Android app (karoo-ext)
  backend/             # TS + Fastify service (Cloud Run)
  docs/
```

## Distribution / dev loop
- Kotlin in Android Studio. Build APK → GitHub release → paste release APK URL into the
  **Hammerhead Companion app**, which pushes to the paired Karoo over the network (no USB).

## Milestones
1. **Kotlin skeleton** — extension registers, reads `OnNavigationState`, draws one static
   pin. Sideload to real Karoo 3. *Proves the hard part (SDK + signing + sideload).*
2. **Node `/build`** — polyline + config → Overpass → JSON. Test with curl, deploy Cloud Run.
3. **Wire together** — Build button, cache, list + pins from real data.
4. **Config UI** — detour slider + category toggles feeding the request.
5. **On-demand nearby** + data field.

## Open items (deferred, not blocking)
- Wikipedia/Wikivoyage stories + Wikimedia photos enrichment.
- Self-hosted Overpass.
- Update checker.
- Opening-hours "open now" evaluation on device.
