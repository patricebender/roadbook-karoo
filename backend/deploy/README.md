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

Builds the container via Cloud Build and deploys with the cost flags above.
CI deploys automatically — see `.github/workflows/` (Workload Identity Federation,
no long-lived keys).

## Teardown (delete everything)

```sh
PROJECT_ID=roadbook-karoo bash deploy/teardown-gcp.sh
```
