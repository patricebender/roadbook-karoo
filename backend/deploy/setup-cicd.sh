#!/usr/bin/env bash
# One-time setup for keyless CI/CD deploys from GitHub Actions to Cloud Run,
# using Workload Identity Federation (no long-lived service-account keys).
# Mirrors the mechanism used in the sibling `sharp6` project.
#
# Idempotent: safe to re-run. After running, it prints the two values to paste
# into .github/workflows/deploy-backend.yml (they are NOT secrets).
set -euo pipefail

PROJECT_ID="${PROJECT_ID:-roadbook-karoo}"
GH_REPO="${GH_REPO:-patricebender/roadbook-karoo}"
POOL="${POOL:-github-pool}"
PROVIDER="${PROVIDER:-github-provider}"
SA_NAME="${SA_NAME:-github-deploy}"
SA_EMAIL="${SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"

echo ">> Project: $PROJECT_ID   Repo: $GH_REPO"
gcloud config set project "$PROJECT_ID" >/dev/null

PROJECT_NUMBER="$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')"

# ---- APIs ----
gcloud services enable \
  iamcredentials.googleapis.com \
  sts.googleapis.com \
  iam.googleapis.com \
  --project "$PROJECT_ID"

# ---- Deploy service account ----
if ! gcloud iam service-accounts describe "$SA_EMAIL" >/dev/null 2>&1; then
  echo ">> Creating service account $SA_EMAIL"
  gcloud iam service-accounts create "$SA_NAME" \
    --display-name="GitHub Actions deployer"
fi

echo ">> Granting deploy roles"
for ROLE in roles/run.admin roles/artifactregistry.writer roles/iam.serviceAccountUser; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:${SA_EMAIL}" \
    --role="$ROLE" \
    --condition=None >/dev/null
done

# ---- Workload Identity Pool ----
if ! gcloud iam workload-identity-pools describe "$POOL" \
      --location=global >/dev/null 2>&1; then
  echo ">> Creating workload identity pool $POOL"
  gcloud iam workload-identity-pools create "$POOL" \
    --location=global \
    --display-name="GitHub Actions pool"
fi

# ---- OIDC provider (restricted to this repo — security linchpin) ----
if ! gcloud iam workload-identity-pools providers describe "$PROVIDER" \
      --location=global --workload-identity-pool="$POOL" >/dev/null 2>&1; then
  echo ">> Creating OIDC provider $PROVIDER (restricted to $GH_REPO)"
  gcloud iam workload-identity-pools providers create-oidc "$PROVIDER" \
    --location=global \
    --workload-identity-pool="$POOL" \
    --display-name="GitHub OIDC" \
    --issuer-uri="https://token.actions.githubusercontent.com" \
    --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository" \
    --attribute-condition="assertion.repository == '${GH_REPO}'"
else
  # Ensure the repo restriction is present even if the provider already existed.
  gcloud iam workload-identity-pools providers update-oidc "$PROVIDER" \
    --location=global \
    --workload-identity-pool="$POOL" \
    --attribute-condition="assertion.repository == '${GH_REPO}'" >/dev/null || true
fi

# ---- Allow the repo's Actions to impersonate the SA ----
POOL_ID="projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${POOL}"
echo ">> Binding workloadIdentityUser for repo $GH_REPO"
gcloud iam service-accounts add-iam-policy-binding "$SA_EMAIL" \
  --role=roles/iam.workloadIdentityUser \
  --member="principalSet://iam.googleapis.com/${POOL_ID}/attribute.repository/${GH_REPO}" \
  >/dev/null

PROVIDER_RESOURCE="${POOL_ID}/providers/${PROVIDER}"

cat <<EOF

>> Done. Put these in .github/workflows/deploy-backend.yml:

  workload_identity_provider: ${PROVIDER_RESOURCE}
  service_account: ${SA_EMAIL}

EOF
