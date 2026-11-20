#!/usr/bin/env bash
set -euo pipefail
BASE_URL="${BASE_URL:-http://localhost:8084}"
FACILITY_ID="${FACILITY_ID:-01950000-0000-7000-8000-000000000004}"
JWT_TOKEN="${JWT_TOKEN:-}"
echo "[HealthCore Smoke] Checking ${BASE_URL}/actuator/health ..."
curl -fsS "${BASE_URL}/actuator/health" | grep -q '"status":"UP"'
echo "[HealthCore Smoke] Health check PASSED."
if [ -n "${JWT_TOKEN}" ]; then
  echo "[HealthCore Smoke] Executing persisted POST /api/v1/pharma/qms/documents ..."
  curl -fsS -X POST "${BASE_URL}/api/v1/pharma/qms/documents" \
    -H "Content-Type: application/json" \
    -H "X-Facility-Id: ${FACILITY_ID}" \
    -H "Authorization: Bearer ${JWT_TOKEN}" \
    -d '{"documentCode": "SOP-SYN-001", "documentVersion": "v1.0", "documentType": "SOP", "title": "Synthetic Tablet Compression Line Clearance", "documentBody": "Verify zero residual tablets prior to batch start.", "authoredBy": "qa.author.syn", "reviewDueDate": "2027-01-01"}'
  echo ""
  echo "[HealthCore Smoke] Persisted workflow smoke PASSED."
else
  echo "[HealthCore Smoke] Set JWT_TOKEN env var to also run authenticated POST workflow check."
fi
