#!/usr/bin/env bash
# Build the Angular frontend and deploy it to the S3+CloudFront stack
# provisioned by infra/terraform.
#
# Usage:
#   ./infra/deploy-frontend.sh
#
# Reads bucket name and distribution ID from `terraform output` — so run it
# from a machine that has the terraform state (or has run `terraform init`
# against the shared S3 backend).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TF_DIR="$SCRIPT_DIR/terraform"

if ! command -v aws >/dev/null 2>&1; then
  echo "error: aws CLI not found" >&2; exit 1
fi
if ! command -v terraform >/dev/null 2>&1; then
  echo "error: terraform not found" >&2; exit 1
fi
if ! command -v npm >/dev/null 2>&1; then
  echo "error: npm not found" >&2; exit 1
fi

BUCKET=$(terraform -chdir="$TF_DIR" output -raw frontend_bucket)
DIST_ID=$(terraform -chdir="$TF_DIR" output -raw cloudfront_distribution_id)

if [[ -z "$BUCKET" || -z "$DIST_ID" ]]; then
  echo "error: terraform outputs missing — has the stack been applied?" >&2
  exit 1
fi

echo "Building frontend..."
cd "$REPO_ROOT/frontend"
npm ci
npm run build

DIST_DIR="$REPO_ROOT/frontend/dist/frontend/browser"
if [[ ! -d "$DIST_DIR" ]]; then
  echo "error: expected build output at $DIST_DIR — Angular output path may have changed" >&2
  exit 1
fi

echo "Syncing to s3://$BUCKET ..."
# Two-pass sync: long cache for hashed assets, short cache for index.html
# (so the next deploy flips users to the new bundle immediately).
aws s3 sync "$DIST_DIR" "s3://$BUCKET" \
  --delete \
  --exclude index.html \
  --cache-control "public, max-age=31536000, immutable"

aws s3 cp "$DIST_DIR/index.html" "s3://$BUCKET/index.html" \
  --cache-control "no-cache, no-store, must-revalidate" \
  --content-type "text/html"

echo "Invalidating CloudFront distribution $DIST_ID ..."
aws cloudfront create-invalidation \
  --distribution-id "$DIST_ID" \
  --paths '/*' \
  --query 'Invalidation.Id' \
  --output text

echo "Done."
