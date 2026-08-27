# Multi-stage build → small runtime image (cheaper storage, faster cold start).
#
# The POI database (pois.sqlite) is built separately by data-pipeline/ and must
# be present in the build context. We bake it into the image so the backend is
# self-contained (no Overpass, no external DB). Building the DB in-image would
# mean downloading a ~600MB OSM extract per deploy — instead we build it once.

FROM node:22-slim AS build
WORKDIR /app
# better-sqlite3 compiles a native addon → needs build toolchain.
RUN apt-get update && apt-get install -y --no-install-recommends python3 make g++ \
    && rm -rf /var/lib/apt/lists/*
COPY package.json package-lock.json ./
RUN npm ci
COPY tsconfig.json ./
COPY src ./src
RUN npm run build && npm prune --omit=dev

FROM node:22-slim AS runtime
ENV NODE_ENV=production
WORKDIR /app
ENV PORT=8080
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY package.json ./
# The pre-built POI database (from `npm run build:poi-db`).
COPY pois.sqlite ./pois.sqlite
USER node
CMD ["node", "dist/server.js"]
