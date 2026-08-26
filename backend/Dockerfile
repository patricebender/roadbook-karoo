# Multi-stage build → small runtime image (cheaper storage, faster cold start).
FROM node:22-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY tsconfig.json ./
COPY src ./src
RUN npm run build && npm prune --omit=dev

FROM node:22-slim AS runtime
ENV NODE_ENV=production
WORKDIR /app
# Cloud Run sets PORT; server reads it. Default 8080 for local runs.
ENV PORT=8080
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY package.json ./
# Run as the built-in non-root node user.
USER node
CMD ["node", "dist/server.js"]
