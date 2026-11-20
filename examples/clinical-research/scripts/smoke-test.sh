#!/usr/bin/env bash
set -euo pipefail
BASE_URL="${BASE_URL:-http://localhost:8087}"
FACILITY_ID="${FACILITY_ID:-01950000-0000-7000-8000-000000000007}"
JWT_TOKEN="${JWT_TOKEN:-}"
echo "[HealthCore Smoke] Checking ${BASE_URL}/actuator/health ..."
curl -fsS "${BASE_URL}/actuator/health" | grep -q '"status":"UP"'
echo "[HealthCore Smoke] Health check PASSED."
if [ -n "${JWT_TOKEN}" ]; then
  echo "[HealthCore Smoke] Executing persisted POST /api/v1/lifesciences/clinical-research/subjects/enroll ..."
  curl -fsS -X POST "${BASE_URL}/api/v1/lifesciences/clinical-research/subjects/enroll" \
    -H "Content-Type: application/json" \
    -H "X-Facility-Id: ${FACILITY_ID}" \
    -H "Authorization: Bearer ${JWT_TOKEN}" \
    -d '{"protocolCode": "SYN-TRIAL-01", "siteCode": "SITE01", "subjectPseudonym": "SUBJ-SYN-001", "armCode": "ARM-A", "eligibilityPassed": true, "econsentVersion": "ICF-v1.0"}'
  echo ""
  echo "[HealthCore Smoke] Persisted workflow smoke PASSED."
else
  echo "[HealthCore Smoke] Set JWT_TOKEN env var to also run authenticated POST workflow check."
fi
