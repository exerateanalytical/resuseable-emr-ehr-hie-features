#!/usr/bin/env bash
# =============================================================================
# Opes HealthCore SDK — PostgreSQL 16+ Point-in-Time Recovery (PITR) Drill
# Author / Architect : Jude Nshome — Opes Health Systems
# Email              : nshome@opesware.com
# Website            : https://www.opeshealthsystems.com
# Location           : Douala, Cameroon
# =============================================================================
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <base-backup-dir> <recovery-target-iso-utc> [restore-pgdata]"
  echo "Example: $0 /var/backups/opes-healthcore/base-20261118T120000Z '2026-11-18 11:55:00+00' /var/lib/postgresql/pitr-drill"
  exit 1
fi

BACKUP_DIR="$1"
RECOVERY_TARGET_TIME="$2"
RESTORE_PGDATA="${3:-/var/lib/postgresql/pitr-drill}"
WAL_ARCHIVE_DIR="${WAL_ARCHIVE_DIR:-/var/backups/opes-healthcore/wal_archive}"

echo "[Opes HealthCore PITR Drill] Verifying SHA-256 archive integrity..."
(cd "${BACKUP_DIR}" && sha256sum -c SHA256SUMS)

rm -rf "${RESTORE_PGDATA}"
mkdir -p "${RESTORE_PGDATA}"
tar -xzf "${BACKUP_DIR}/base.tar.gz" -C "${RESTORE_PGDATA}"
if [[ -f "${BACKUP_DIR}/pg_wal.tar.gz" ]]; then
  mkdir -p "${RESTORE_PGDATA}/pg_wal"
  tar -xzf "${BACKUP_DIR}/pg_wal.tar.gz" -C "${RESTORE_PGDATA}/pg_wal"
fi

touch "${RESTORE_PGDATA}/recovery.signal"
cat >> "${RESTORE_PGDATA}/postgresql.auto.conf" <<EOF
restore_command = 'cp ${WAL_ARCHIVE_DIR}/%f %p'
recovery_target_time = '${RECOVERY_TARGET_TIME}'
recovery_target_action = 'promote'
EOF

echo "[Opes HealthCore PITR Drill] Prepared PITR cluster directory at ${RESTORE_PGDATA} targeting ${RECOVERY_TARGET_TIME}"
