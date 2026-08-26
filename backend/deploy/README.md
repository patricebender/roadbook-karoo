# Backend deployment (Google Cloud Run)

Stateless container on Cloud Run, **scales to zero** — near-zero cost for personal use.

## Live service

- Project: `roadbook-karoo` (europe-west1)
- URL: set after deploy; `gcloud run services describe roadbook-backend --region europe-west1 --format='value(status.url)'`

## Cost guardrails

- `min-instances=0` — no idle charges (scale to zero).
- `max-instances=2` — caps runaway/abuse spend.
- `--cpu-throttling` — CPU billed only during request processing.
- `256Mi` memory, `concurrency=20`.
- In-app rate limiting (`@fastify/rate-limit`, 30 req/min/IP).
- €5/month billing budget alert (50/90/100%).
- Artifact Registry cleanup policy: keep only 3 most recent images.

Expected cost: well within GCP free tier, realistically ~€0/month.

## One-time setup

```sh
export BILLING_ACCOUNT=<your-billing-account-id>   # e.g. 017683-62725E-65892F
export PROJECT_ID=roadbook-karoo
bash deploy/setup-gcp.sh
```

Creates the project, links billing, enables APIs, creates the image repo + cleanup
policy. (Budget is created manually; currency MUST match the billing account, e.g. EUR.)

## Deploy

```sh
export PROJECT_ID=roadbook-karoo
bash deploy/deploy.sh
```

`deploy.sh` is the manual fallback (builds via Cloud Build). Normally you don't run it —
CI deploys on every push (below).

## CI/CD (mirrors the sibling `sharp6` project)

- **Backend deploy** — `.github/workflows/deploy-backend.yml` runs on every push to `main`
  that touches `backend/**`. It authenticates keyless via Workload Identity Federation
  (no stored keys), builds + pushes the image in the runner, deploys to Cloud Run with the
  cost flags above, smoke-tests `/health`, and prunes to the 5 most recent SHA-tagged images.
- **APK release** — `.github/workflows/release.yml` runs on pushes touching `extension/**`,
  auto-versions as `vYYYY.MM.DD-<sha>`, builds the APK, and publishes a GitHub Release.

### One-time WIF setup

```sh
PROJECT_ID=roadbook-karoo bash deploy/setup-cicd.sh
```

Creates the `github-deploy` service account, a Workload Identity Pool + OIDC provider
**restricted to `patricebender/roadbook-karoo`**, and the impersonation binding. It prints
the `workload_identity_provider` + `service_account` values already hardcoded in
`deploy-backend.yml` (they are identifiers, not secrets).

## Teardown (delete everything)

```sh
PROJECT_ID=roadbook-karoo bash deploy/teardown-gcp.sh
```
