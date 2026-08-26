#!/usr/bin/env bash
# Delete everything this project created. Because it's a dedicated project,
# deleting the project removes the Cloud Run service, images, and stops all
# billing. The budget (on the billing account, not the project) is removed
# separately.
set -euo pipefail

PROJECT_ID="${PROJECT_ID:-roadbook-karoo}"

echo ">> This will DELETE project $PROJECT_ID and all its resources."
read -r -p "Type the project id to confirm: " confirm
[ "$confirm" = "$PROJECT_ID" ] || { echo "aborted"; exit 1; }

gcloud projects delete "$PROJECT_ID"
echo ">> Project scheduled for deletion (recoverable for ~30 days)."
echo ">> To remove the billing budget, run:"
echo "   gcloud billing budgets list --billing-account=<ACCOUNT>"
echo "   gcloud billing budgets delete <BUDGET_ID> --billing-account=<ACCOUNT>"
