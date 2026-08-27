import Fastify from "fastify";
import cors from "@fastify/cors";
import rateLimit from "@fastify/rate-limit";
import {
  ALL_CATEGORIES,
  PAGE_SIZE,
  type BuildRequest,
  type NearbyRequest,
  type Category,
  type PagePoi,
  type Poi,
} from "./contract.js";
import { decodePolyline } from "./polyline.js";
import { buildAlongRoute, buildNearby } from "./overpass.js";
import * as buildStore from "./buildStore.js";

const app = Fastify({ logger: true, trustProxy: true });
await app.register(cors, { origin: true });

// Cost guardrail: cap requests per client. Overpass-backed builds are expensive,
// so keep this modest. Tunable via env for load testing.
await app.register(rateLimit, {
  max: Number(process.env.RATE_LIMIT_MAX ?? 30),
  timeWindow: process.env.RATE_LIMIT_WINDOW ?? "1 minute",
});

function validCategories(input: unknown): Category[] {
  if (!Array.isArray(input)) return [];
  return input.filter((c): c is Category =>
    (ALL_CATEGORIES as string[]).includes(c),
  );
}

/** Trim a stored POI to the minimal page shape (drops heavy tags). */
function toPagePoi(p: Poi): PagePoi {
  return {
    id: p.id,
    lat: p.lat,
    lng: p.lng,
    type: p.type,
    name: p.name,
    distancesAlongRoute: p.distancesAlongRoute,
  };
}

function storeAndSummarize(pois: Poi[]) {
  const buildId = buildStore.create(pois);
  return {
    buildId,
    totalCount: pois.length,
    pageSize: PAGE_SIZE,
    pageCount: Math.ceil(pois.length / PAGE_SIZE),
  };
}

app.get("/health", async () => ({ ok: true }));

// Start a route build: compute POIs, store them, return a handle to page through.
app.post<{ Body: BuildRequest }>("/build/start", async (req, reply) => {
  const { polyline, detourMeters } = req.body ?? ({} as BuildRequest);
  const categories = validCategories(req.body?.categories);
  if (!polyline || typeof polyline !== "string") {
    return reply.code(400).send({ error: "polyline (string) required" });
  }
  if (!Number.isFinite(detourMeters) || detourMeters <= 0 || detourMeters > 5000) {
    return reply.code(400).send({ error: "detourMeters must be 1..5000" });
  }
  if (categories.length === 0) {
    return reply.code(400).send({ error: "at least one valid category required" });
  }
  const route = decodePolyline(polyline);
  if (route.length < 2) {
    return reply.code(400).send({ error: "polyline decoded to < 2 points" });
  }
  const pois = await buildAlongRoute(route, detourMeters, categories);
  return storeAndSummarize(pois);
});

// Start a nearby build (fallback when no route is loaded).
app.post<{ Body: NearbyRequest }>("/nearby/start", async (req, reply) => {
  const { lat, lng, radiusMeters } = req.body ?? ({} as NearbyRequest);
  const categories = validCategories(req.body?.categories);
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) {
    return reply.code(400).send({ error: "lat/lng required" });
  }
  if (!Number.isFinite(radiusMeters) || radiusMeters <= 0 || radiusMeters > 5000) {
    return reply.code(400).send({ error: "radiusMeters must be 1..5000" });
  }
  if (categories.length === 0) {
    return reply.code(400).send({ error: "at least one valid category required" });
  }
  const pois = await buildNearby({ lat, lng }, radiusMeters, categories);
  return storeAndSummarize(pois);
});

// Fetch one page of a previously started build.
app.get<{ Params: { id: string; n: string } }>(
  "/build/:id/page/:n",
  async (req, reply) => {
    const pois = buildStore.get(req.params.id);
    if (!pois) {
      return reply.code(404).send({ error: "build not found or expired" });
    }
    const n = Number(req.params.n);
    if (!Number.isInteger(n) || n < 0) {
      return reply.code(400).send({ error: "invalid page number" });
    }
    const start = n * PAGE_SIZE;
    const page = pois.slice(start, start + PAGE_SIZE).map(toPagePoi);
    return { page: n, pois: page };
  },
);

const port = Number(process.env.PORT ?? 8080);
app
  .listen({ port, host: "0.0.0.0" })
  .catch((err) => {
    app.log.error(err);
    process.exit(1);
  });
