#!/usr/bin/env bash
set -euo pipefail
BASE_URL="${BASE_URL:-http://localhost:8086}"
FACILITY_ID="${FACILITY_ID:-01950000-0000-7000-8000-000000000006}"
JWT_TOKEN="${JWT_TOKEN:-}"
echo "[HealthCore Smoke] Checking ${BASE_URL}/actuator/health ..."
curl -fsS "${BASE_URL}/actuator/health" | grep -q '"status":"UP"'
echo "[HealthCore Smoke] Health check PASSED."
if [ -n "${JWT_TOKEN}" ]; then
  echo "[HealthCore Smoke] Executing persisted POST /api/v1/lifesciences/biobank/aliquots ..."
  curl -fsS -X POST "${BASE_URL}/api/v1/lifesciences/biobank/aliquots" \
    -H "Content-Type: application/json" \
    -H "X-Facility-Id: ${FACILITY_ID}" \
    -H "Authorization: Bearer ${JWT_TOKEN}" \
    -d '{"aliquotBarcode": "SYN-CRYO-0001", "donorPseudonym": "DONOR-SYN-01", "specimenMatrix": "PLASMA", "volumeMicroliters": 500, "freezerCode": "LN2-01", "rackCode": "R1", "boxCode": "B1", "slotCode": "A1", "consentActive": true}'
  echo ""
  echo "[HealthCore Smoke] Persisted workflow smoke PASSED."
else
  echo "[HealthCore Smoke] Set JWT_TOKEN env var to also run authenticated POST workflow check."
fi
