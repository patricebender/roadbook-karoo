#!/usr/bin/env bash
# One-time GCP bootstrap for the Roadbook backend.
# Idempotent: safe to re-run. Creates a dedicated project so costs are isolated
# and teardown is trivial (see teardown-gcp.sh).
#
# Requires: gcloud authenticated as an account that can create projects and has
# access to the billing account below.
set -euo pipefail

# ---- Config (override via env) ----
PROJECT_ID="${PROJECT_ID:-roadbook-karoo-$(date +%Y%m)}"
BILLING_ACCOUNT="${BILLING_ACCOUNT:?set BILLING_ACCOUNT, e.g. 017683-62725E-65892F}"
REGION="${REGION:-europe-west1}"
REPO="${REPO:-roadbook}"                 # Artifact Registry repo
SERVICE="${SERVICE:-roadbook-backend}"   # Cloud Run service
BUDGET_AMOUNT="${BUDGET_AMOUNT:-5EUR}"   # monthly budget alert (currency MUST match billing account)

echo ">> Project: $PROJECT_ID  Region: $REGION  Billing: $BILLING_ACCOUNT"

# ---- Project ----
if ! gcloud projects describe "$PROJECT_ID" >/dev/null 2>&1; then
  echo ">> Creating project $PROJECT_ID"
  gcloud projects create "$PROJECT_ID" --name="Roadbook Karoo"
fi
gcloud billing projects link "$PROJECT_ID" --billing-account="$BILLING_ACCOUNT"
gcloud config set project "$PROJECT_ID"

# ---- APIs ----
echo ">> Enabling APIs"
gcloud services enable \
  run.googleapis.com \
  cloudbuild.googleapis.com \
  artifactregistry.googleapis.com \
  iamcredentials.googleapis.com \
  --project "$PROJECT_ID"

# ---- Artifact Registry (with cleanup policy so old images don't accrue cost) ----
if ! gcloud artifacts repositories describe "$REPO" --location="$REGION" >/dev/null 2>&1; then
  echo ">> Creating Artifact Registry repo $REPO"
  gcloud artifacts repositories create "$REPO" \
    --repository-format=docker \
    --location="$REGION" \
    --description="Roadbook backend images"
fi
# Keep only the 3 most recent images.
gcloud artifacts repositories set-cleanup-policies "$REPO" \
  --location="$REGION" \
  --policy=<(cat <<'JSON'
[
  {
    "name": "keep-recent",
    "action": {"type": "Keep"},
    "mostRecentVersions": {"keepCount": 3}
  }
]
JSON
) || echo "   (cleanup policy skipped — non-fatal)"

echo ">> GCP bootstrap complete. Next: deploy.sh"
