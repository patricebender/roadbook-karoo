import Fastify from "fastify";
import cors from "@fastify/cors";
import {
  ALL_CATEGORIES,
  type BuildRequest,
  type NearbyRequest,
  type Category,
} from "./contract.js";
import { decodePolyline } from "./polyline.js";
import { buildAlongRoute, buildNearby } from "./overpass.js";

const app = Fastify({ logger: true });
await app.register(cors, { origin: true });

function validCategories(input: unknown): Category[] {
  if (!Array.isArray(input)) return [];
  return input.filter((c): c is Category =>
    (ALL_CATEGORIES as string[]).includes(c),
  );
}

app.get("/health", async () => ({ ok: true }));

app.post<{ Body: BuildRequest }>("/build", async (req, reply) => {
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
  return { pois };
});

app.post<{ Body: NearbyRequest }>("/nearby", async (req, reply) => {
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
  return { pois };
});

const port = Number(process.env.PORT ?? 8080);
app
  .listen({ port, host: "0.0.0.0" })
  .catch((err) => {
    app.log.error(err);
    process.exit(1);
  });
