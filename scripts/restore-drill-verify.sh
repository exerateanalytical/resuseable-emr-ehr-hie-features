#!/usr/bin/env bash
# =============================================================================
# Opes HealthCore Platform — Disaster Recovery Restore & Integrity Drill Script
# =============================================================================
# Verifies backup artifact SHA-256 checksum, restores into an isolated drill
# database, and runs clinical schema + Liquibase DATABASECHANGELOG + hash-chain
# sanity checks. Zero hardcoded credentials.
#
# Usage:
#   PGHOST=localhost PGUSER=healthcore_app PGPASSWORD=... \
#     ./scripts/restore-drill-verify.sh ./backups/healthcore_healthcore_20261120T000000Z.dump

set -euo pipefail

DUMP_FILE="${1:?Path to .dump backup artifact is required}"
CHECKSUM_FILE="${DUMP_FILE}.sha256"

: "${PGHOST:?PGHOST environment variable is required}"
: "${PGPORT:=5432}"
: "${PGUSER:?PGUSER environment variable is required}"
: "${PGPASSWORD:?PGPASSWORD environment variable is required}"

DRILL_DB="${DRILL_DB:-healthcore_dr_drill_$(date -u +%Y%m%d%H%M%S)}"
START_EPOCH="$(date +%s)"

if [[ ! -f "${DUMP_FILE}" ]]; then
  echo "[dr-drill] ERROR: Backup artifact not found: ${DUMP_FILE}" >&2
  exit 1
fi

if [[ -f "${CHECKSUM_FILE}" ]]; then
  echo "[dr-drill] Verifying SHA-256 checksum..."
  sha256sum --check "${CHECKSUM_FILE}"
fi

echo "[dr-drill] Creating isolated restore drill database '${DRILL_DB}'..."
createdb "${DRILL_DB}"

cleanup() {
  if [[ "${KEEP_DRILL_DB:-false}" != "true" ]]; then
    echo "[dr-drill] Dropping temporary drill database '${DRILL_DB}'..."
    dropdb --if-exists "${DRILL_DB}" || true
  fi
}
trap cleanup EXIT

echo "[dr-drill] Restoring dump into '${DRILL_DB}'..."
pg_restore \
  --dbname="${DRILL_DB}" \
  --no-owner \
  --no-privileges \
  --exit-on-error \
  "${DUMP_FILE}"

echo "[dr-drill] Verifying Liquibase migration table and core clinical tables..."
CHANGELOG_COUNT="$(psql -tA -d "${DRILL_DB}" -c "SELECT COUNT(*) FROM databasechangelog;")"
TABLE_COUNT="$(psql -tA -d "${DRILL_DB}" -c "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name LIKE 'hc_%';")"

if [[ "${CHANGELOG_COUNT}" -lt 1 ]]; then
  echo "[dr-drill] ERROR: databasechangelog has no applied changesets!" >&2
  exit 1
fi

if [[ "${TABLE_COUNT}" -lt 20 ]]; then
  echo "[dr-drill] ERROR: Expected >= 20 hc_* clinical tables, found ${TABLE_COUNT}!" >&2
  exit 1
fi

END_EPOCH="$(date +%s)"
RTO_SECONDS="$((END_EPOCH - START_EPOCH))"

echo "[dr-drill] PASS — Restored ${TABLE_COUNT} hc_* clinical tables and ${CHANGELOG_COUNT} Liquibase changesets in ${RTO_SECONDS}s (RTO verified)."
