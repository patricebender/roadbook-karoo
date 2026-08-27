#!/usr/bin/env bash
# Build the container from source and deploy to Cloud Run.
# Cost-minimising flags: scale to zero, low max-instances, small memory,
# request-based CPU billing (no charge while idle).
set -euo pipefail

PROJECT_ID="${PROJECT_ID:?set PROJECT_ID}"
REGION="${REGION:-europe-west1}"
REPO="${REPO:-roadbook}"
SERVICE="${SERVICE:-roadbook-backend}"
IMAGE="${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO}/${SERVICE}:$(git rev-parse --short HEAD 2>/dev/null || echo latest)"

cd "$(dirname "$0")/.."   # backend/

echo ">> Building $IMAGE via Cloud Build"
gcloud builds submit --project "$PROJECT_ID" --tag "$IMAGE" .

echo ">> Deploying to Cloud Run"
gcloud run deploy "$SERVICE" \
  --project "$PROJECT_ID" \
  --region "$REGION" \
  --image "$IMAGE" \
  --service-account "roadbook-runtime@${PROJECT_ID}.iam.gserviceaccount.com" \
  --allow-unauthenticated \
  --min-instances=0 \
  --max-instances=2 \
  --concurrency=20 \
  --cpu=1 \
  --memory=256Mi \
  --timeout=60s \
  --cpu-throttling \
  --set-env-vars="NODE_ENV=production"

echo ">> URL:"
gcloud run services describe "$SERVICE" --project "$PROJECT_ID" --region "$REGION" \
  --format='value(status.url)'
