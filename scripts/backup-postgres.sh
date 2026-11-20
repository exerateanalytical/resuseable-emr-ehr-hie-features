#!/usr/bin/env bash
# =============================================================================
# Opes HealthCore Platform — Encrypted PostgreSQL Backup Script
# =============================================================================
# Produces a consistent custom-format pg_dump backup, computes SHA-256 checksum,
# writes a DR manifest JSON, and optionally encrypts with age/gpg when enabled.
# Requires environment variables (zero hardcoded credentials):
#   PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD
# Optional:
#   BACKUP_DIR (default: ./backups), BACKUP_GPG_RECIPIENT

set -euo pipefail

: "${PGHOST:?PGHOST environment variable is required}"
: "${PGPORT:=5432}"
: "${PGDATABASE:?PGDATABASE environment variable is required}"
: "${PGUSER:?PGUSER environment variable is required}"
: "${PGPASSWORD:?PGPASSWORD environment variable is required}"

BACKUP_DIR="${BACKUP_DIR:-./backups}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BASENAME="healthcore_${PGDATABASE}_${TIMESTAMP}"
DUMP_FILE="${BACKUP_DIR}/${BASENAME}.dump"
CHECKSUM_FILE="${DUMP_FILE}.sha256"
MANIFEST_FILE="${BACKUP_DIR}/${BASENAME}.manifest.json"

mkdir -p "${BACKUP_DIR}"
umask 077

echo "[backup] Starting consistent PostgreSQL backup for database '${PGDATABASE}' at ${TIMESTAMP}..."
pg_dump \
  --format=custom \
  --compress=9 \
  --no-owner \
  --no-privileges \
  --verbose \
  --file="${DUMP_FILE}"

sha256sum "${DUMP_FILE}" > "${CHECKSUM_FILE}"
DUMP_SHA256="$(awk '{print $1}' "${CHECKSUM_FILE}")"
DUMP_BYTES="$(wc -c < "${DUMP_FILE}" | tr -d ' ')"

ENCRYPTED="false"
FINAL_ARTIFACT="${DUMP_FILE}"
if [[ -n "${BACKUP_GPG_RECIPIENT:-}" ]]; then
  gpg --batch --yes --encrypt --recipient "${BACKUP_GPG_RECIPIENT}" --output "${DUMP_FILE}.gpg" "${DUMP_FILE}"
  rm -f "${DUMP_FILE}"
  FINAL_ARTIFACT="${DUMP_FILE}.gpg"
  sha256sum "${FINAL_ARTIFACT}" > "${FINAL_ARTIFACT}.sha256"
  DUMP_SHA256="$(awk '{print $1}' "${FINAL_ARTIFACT}.sha256")"
  ENCRYPTED="true"
fi

cat > "${MANIFEST_FILE}" <<EOF
{
  "platform": "Opes HealthCore HIS",
  "database": "${PGDATABASE}",
  "host": "${PGHOST}",
  "timestampUtc": "${TIMESTAMP}",
  "artifactPath": "${FINAL_ARTIFACT}",
  "sizeBytes": ${DUMP_BYTES},
  "sha256": "${DUMP_SHA256}",
  "encrypted": ${ENCRYPTED}
}
EOF

echo "[backup] Completed successfully. Manifest: ${MANIFEST_FILE}"
