#!/usr/bin/env bash
# Bootstraps the S3 bucket that holds Terraform remote state, then writes
# backend.hcl so `terraform init -backend-config=backend.hcl` just works.
#
# Usage:
#   ./bootstrap.sh                # uses defaults: region eu-central-1, bucket rainalator-tfstate-<account-id>
#   ./bootstrap.sh eu-west-1      # custom region
#   ./bootstrap.sh eu-west-1 my-bucket-name  # custom region + bucket name
#
# Safe to re-run: if the bucket already exists (in your account), it just rewrites backend.hcl.

set -euo pipefail

REGION="${1:-eu-central-1}"
BUCKET="${2:-}"

if ! command -v aws >/dev/null 2>&1; then
  echo "error: aws CLI not found in PATH" >&2
  exit 1
fi

ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
if [[ -z "$BUCKET" ]]; then
  BUCKET="rainalator-tfstate-${ACCOUNT_ID}"
fi

echo "Region:  $REGION"
echo "Bucket:  $BUCKET"

# Create bucket (idempotent — ignore error if already owned by us).
if aws s3api head-bucket --bucket "$BUCKET" 2>/dev/null; then
  echo "Bucket already exists, skipping creation."
else
  if [[ "$REGION" == "us-east-1" ]]; then
    aws s3api create-bucket --bucket "$BUCKET" --region "$REGION"
  else
    aws s3api create-bucket \
      --bucket "$BUCKET" \
      --region "$REGION" \
      --create-bucket-configuration "LocationConstraint=$REGION"
  fi
fi

# Versioning (recover from corrupted state) + default encryption + block all public access.
aws s3api put-bucket-versioning --bucket "$BUCKET" \
  --versioning-configuration Status=Enabled

aws s3api put-bucket-encryption --bucket "$BUCKET" \
  --server-side-encryption-configuration '{
    "Rules": [{"ApplyServerSideEncryptionByDefault": {"SSEAlgorithm": "AES256"}}]
  }'

aws s3api put-public-access-block --bucket "$BUCKET" \
  --public-access-block-configuration \
  "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true"

# Write backend.hcl for terraform init.
cat > backend.hcl <<EOF
bucket = "$BUCKET"
region = "$REGION"
EOF

echo
echo "Wrote backend.hcl. Next steps:"
echo "  1. cp terraform.tfvars.example terraform.tfvars   # then edit values"
echo "  2. terraform init -backend-config=backend.hcl"
echo "  3. terraform apply"
