#!/usr/bin/env bash
# =============================================================================
# Opes HealthCore SDK — PostgreSQL 16+ Physical Base Backup & PITR Script
# Author / Architect : Jude Nshome — Opes Health Systems
# Email              : nshome@opesware.com
# Website            : https://www.opeshealthsystems.com
# Location           : Douala, Cameroon
# =============================================================================
set -euo pipefail

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-healthcore_app}"
PGDATABASE="${PGDATABASE:-healthcore}"
BACKUP_ROOT="${BACKUP_ROOT:-/var/backups/opes-healthcore}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
TARGET_DIR="${BACKUP_ROOT}/base-${STAMP}"

mkdir -p "${TARGET_DIR}"

echo "[Opes HealthCore PITR] Starting physical pg_basebackup -> ${TARGET_DIR}"
pg_basebackup \
  --host="${PGHOST}" \
  --port="${PGPORT}" \
  --username="${PGUSER}" \
  --pgdata="${TARGET_DIR}" \
  --format=tar \
  --gzip \
  --wal-method=stream \
  --checkpoint=fast \
  --verbose

sha256sum "${TARGET_DIR}"/*.tar.gz > "${TARGET_DIR}/SHA256SUMS"
echo "[Opes HealthCore PITR] Logical verify dump -> ${TARGET_DIR}/schema-logical.sql.gz"
pg_dump --host="${PGHOST}" --port="${PGPORT}" --username="${PGUSER}" --dbname="${PGDATABASE}" \
  | gzip > "${TARGET_DIR}/schema-logical.sql.gz"

echo "[Opes HealthCore PITR] Complete. Checksum manifest written to ${TARGET_DIR}/SHA256SUMS"
