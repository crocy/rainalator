#!/usr/bin/env bash
#
# Pull radar_scans data from the prod DB into the local docker DB.
#
# The prod DB port is not published anywhere, so the dump travels via an S3
# bounce: SSM RunShellScript executes `psql COPY ... TO STDOUT | gzip` inside
# the prod DB container and uploads to the archive bucket (the instance role
# can already write there); this script then downloads, restores into a
# staging table, and merges with ON CONFLICT DO NOTHING, so re-runs and
# overlaps with locally-ingested scans are safe.
#
# Usage:
#   infra/pull-prod-db.sh             # last 30 days
#   infra/pull-prod-db.sh --days 7    # last 7 days
#   infra/pull-prod-db.sh --full      # entire radar_scans table
#
# Requirements: aws CLI (creds with ssm:SendCommand + s3 read/delete on the
# archive bucket), jq, docker, gzip. Instance id and bucket are resolved from
# terraform outputs; override via env to skip terraform:
#   RAINALATOR_EC2_INSTANCE_ID, RAINALATOR_ARCHIVE_BUCKET,
#   RAINALATOR_DB_CONTAINER (default rainalator-db), AWS_REGION (default
#   eu-central-1).
#
# The local schema must already exist (start the backend once so Flyway
# creates it) — this script never touches schema, only rows.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TF_DIR="${RAINALATOR_TF_DIR:-$REPO_ROOT/infra/terraform}"
AWS_REGION="${AWS_REGION:-eu-central-1}"
DB_CONTAINER="${RAINALATOR_DB_CONTAINER:-rainalator-db}"
# Fixed by container_name in docker-compose.prod.yml, unrelated to the local name.
PROD_DB_CONTAINER=rainalator-db
PGUSER=rainalator
PGDB=rainalator
COLUMNS="scan_time, ingested_at, source_radars, raster_data, bbox, scan_metadata"
STAGING_TABLE=radar_scans_import
REMOTE_TIMEOUT_SECS=3600

DAYS=30
FULL=0

usage() { sed -n '2,26p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

log()  { echo "[pull-prod-db] $*"; }
fail() { echo "[pull-prod-db] ERROR: $*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --days)
      [[ "${2:-}" =~ ^[0-9]+$ ]] || fail "--days requires a positive integer"
      DAYS="$2"; shift 2 ;;
    --full)
      FULL=1; shift ;;
    -h|--help)
      usage; exit 0 ;;
    *)
      fail "unknown argument: $1 (see --help)" ;;
  esac
done

for tool in aws jq docker gzip; do
  command -v "$tool" >/dev/null || fail "required tool not found: $tool"
done
aws sts get-caller-identity >/dev/null 2>&1 || fail "AWS credentials not working (aws sts get-caller-identity failed)"

resolve_tf_output() {
  terraform -chdir="$TF_DIR" output -raw "$1" 2>/dev/null \
    || fail "cannot resolve terraform output '$1' (set RAINALATOR_EC2_INSTANCE_ID / RAINALATOR_ARCHIVE_BUCKET to skip terraform)"
}

INSTANCE_ID="${RAINALATOR_EC2_INSTANCE_ID:-$(resolve_tf_output ec2_instance_id)}"
BUCKET="${RAINALATOR_ARCHIVE_BUCKET:-$(resolve_tf_output archive_bucket)}"

local_psql() {
  docker exec -i "$DB_CONTAINER" psql -U "$PGUSER" -d "$PGDB" -v ON_ERROR_STOP=1 "$@"
}

############################################
# Local DB up + schema present
############################################

if ! docker ps --format '{{.Names}}' | grep -qx "$DB_CONTAINER"; then
  if docker ps -a --format '{{.Names}}' | grep -qx "$DB_CONTAINER"; then
    log "starting existing container $DB_CONTAINER"
    docker start "$DB_CONTAINER" >/dev/null
  else
    log "creating local db via docker compose"
    docker compose -f "$REPO_ROOT/docker-compose.yml" up -d db
  fi
fi

for _ in $(seq 1 30); do
  docker exec "$DB_CONTAINER" pg_isready -U "$PGUSER" -d "$PGDB" >/dev/null 2>&1 && break
  sleep 2
done
docker exec "$DB_CONTAINER" pg_isready -U "$PGUSER" -d "$PGDB" >/dev/null 2>&1 \
  || fail "local db ($DB_CONTAINER) did not become ready"

[[ -n "$(local_psql -tAc "SELECT to_regclass('public.radar_scans')")" ]] \
  || fail "radar_scans table missing locally — start the backend once so Flyway creates the schema"

############################################
# Remote dump -> S3 (via SSM RunShellScript)
############################################

WHERE=""
RANGE_DESC="full table"
if [[ "$FULL" -eq 0 ]]; then
  WHERE="WHERE scan_time >= now() - interval '$DAYS days'"
  RANGE_DESC="last $DAYS days"
fi

KEY="db-dumps/radar_scans-$(date -u +%Y%m%dT%H%M%SZ)-$$.copy.gz"
S3_URI="s3://$BUCKET/$KEY"

REMOTE_PSQL="docker exec $PROD_DB_CONTAINER psql -U $PGUSER -d $PGDB -v ON_ERROR_STOP=1"
PARAMS_FILE="$(mktemp)"
jq -n \
  --arg c0 "set -euo pipefail" \
  --arg c1 "ROWS=\$($REMOTE_PSQL -tAc \"SELECT count(*) FROM radar_scans $WHERE\")" \
  --arg c2 "echo rows=\$ROWS" \
  --arg c3 "$REMOTE_PSQL -c \"COPY (SELECT $COLUMNS FROM radar_scans $WHERE ORDER BY scan_time) TO STDOUT\" | gzip | aws s3 cp - $S3_URI --region $AWS_REGION" \
  --arg timeout "$REMOTE_TIMEOUT_SECS" \
  '{commands: [$c0, $c1, $c2, $c3], executionTimeout: [$timeout]}' > "$PARAMS_FILE"

# A failed remote dump can still leave a (truncated) object — s3 cp completes
# on EOF before pipefail propagates — so delete whenever the command was sent,
# not just on success; s3 rm on a never-created key is a harmless no-op. The
# db-dumps/ lifecycle rule (main.tf) mops up whatever this trap can't reach.
DUMP_COMMAND_SENT=0
cleanup() {
  rm -f "$PARAMS_FILE"
  if [[ "$DUMP_COMMAND_SENT" -eq 1 ]]; then
    aws s3 rm "$S3_URI" --region "$AWS_REGION" >/dev/null 2>&1 \
      || log "WARNING: could not delete $S3_URI — remove it manually"
  fi
  local_psql -c "DROP TABLE IF EXISTS $STAGING_TABLE" >/dev/null 2>&1 || true
}
trap cleanup EXIT

log "dumping $RANGE_DESC on prod ($INSTANCE_ID) -> $S3_URI"
COMMAND_ID=$(aws ssm send-command \
  --instance-ids "$INSTANCE_ID" \
  --document-name "AWS-RunShellScript" \
  --comment "rainalator: dump radar_scans ($RANGE_DESC) to S3" \
  --parameters "file://$PARAMS_FILE" \
  --region "$AWS_REGION" \
  --query 'Command.CommandId' --output text)
DUMP_COMMAND_SENT=1

STATUS=InProgress
ELAPSED=0
while [[ "$STATUS" == "InProgress" || "$STATUS" == "Pending" || "$STATUS" == "Delayed" ]]; do
  sleep 5; ELAPSED=$((ELAPSED + 5))
  # Ceiling above executionTimeout so SSM, not us, is the one that times out.
  [[ "$ELAPSED" -le $((REMOTE_TIMEOUT_SECS + 300)) ]] || fail "remote dump did not finish within ${ELAPSED}s"
  STATUS=$(aws ssm get-command-invocation \
    --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" --region "$AWS_REGION" \
    --query 'Status' --output text 2>/dev/null || echo Pending)
  (( ELAPSED % 30 == 0 )) && log "remote dump: $STATUS (${ELAPSED}s)"
done

if [[ "$STATUS" != "Success" ]]; then
  aws ssm get-command-invocation \
    --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" --region "$AWS_REGION" \
    --query 'StandardErrorContent' --output text >&2 || true
  fail "remote dump failed with status $STATUS"
fi

REMOTE_ROWS=$(aws ssm get-command-invocation \
  --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" --region "$AWS_REGION" \
  --query 'StandardOutputContent' --output text | grep -oP '(?<=^rows=)\d+' || true)
[[ -n "$REMOTE_ROWS" ]] || fail "could not parse row count from remote output"
log "remote dump complete: $REMOTE_ROWS rows"

############################################
# Download -> staging -> idempotent merge
############################################

log "restoring into local staging table"
local_psql -c "SET client_min_messages = warning; DROP TABLE IF EXISTS $STAGING_TABLE; CREATE UNLOGGED TABLE $STAGING_TABLE (LIKE radar_scans)" >/dev/null
aws s3 cp "$S3_URI" - --region "$AWS_REGION" | gunzip \
  | local_psql -c "COPY $STAGING_TABLE ($COLUMNS) FROM STDIN" >/dev/null

STAGED=$(local_psql -tAc "SELECT count(*) FROM $STAGING_TABLE")
[[ "$STAGED" == "$REMOTE_ROWS" ]] \
  || fail "staged $STAGED rows but prod reported $REMOTE_ROWS — transfer incomplete, aborting before merge"

INSERTED=$(local_psql -tAc "WITH ins AS (
    INSERT INTO radar_scans ($COLUMNS)
    SELECT $COLUMNS FROM $STAGING_TABLE
    ON CONFLICT (scan_time) DO NOTHING
    RETURNING 1
  ) SELECT count(*) FROM ins")

TOTAL=$(local_psql -tAc "SELECT count(*) FROM radar_scans")
RANGE=$(local_psql -tAc "SELECT min(scan_time) || ' .. ' || max(scan_time) FROM radar_scans")

log "merged: $INSERTED inserted, $((STAGED - INSERTED)) already present (of $STAGED staged)"
log "local radar_scans now: $TOTAL rows, $RANGE"
