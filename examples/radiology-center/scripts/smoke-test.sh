#!/usr/bin/env bash
set -euo pipefail
BASE_URL="${BASE_URL:-http://localhost:8083}"
FACILITY_ID="${FACILITY_ID:-01950000-0000-7000-8000-000000000003}"
JWT_TOKEN="${JWT_TOKEN:-}"
echo "[HealthCore Smoke] Checking ${BASE_URL}/actuator/health ..."
curl -fsS "${BASE_URL}/actuator/health" | grep -q '"status":"UP"'
echo "[HealthCore Smoke] Health check PASSED."
if [ -n "${JWT_TOKEN}" ]; then
  echo "[HealthCore Smoke] Executing persisted POST /api/v1/diagnostics/core/catalog ..."
  curl -fsS -X POST "${BASE_URL}/api/v1/diagnostics/core/catalog" \
    -H "Content-Type: application/json" \
    -H "X-Facility-Id: ${FACILITY_ID}" \
    -H "Authorization: Bearer ${JWT_TOKEN}" \
    -d '{"testCode": "RAD-CXR-PA", "testName": "Chest X-Ray PA/Lateral", "category": "RADIOLOGY", "unitOfMeasure": "N/A", "referenceRange": "Normal cardiopulmonary silhouette", "criticalLow": null, "criticalHigh": null, "turnaroundMinutes": 45, "sendOutLabName": null}'
  echo ""
  echo "[HealthCore Smoke] Persisted workflow smoke PASSED."
else
  echo "[HealthCore Smoke] Set JWT_TOKEN env var to also run authenticated POST workflow check."
fi
