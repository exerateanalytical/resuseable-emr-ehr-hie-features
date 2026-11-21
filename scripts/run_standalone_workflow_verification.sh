#!/usr/bin/env bash
# Verify real standalone application workflows. Synthetic records only.
# This execution gate does not replace clinical/security/regulatory review.
set -euo pipefail
cd "$(dirname "$0")/.."

for tool in python3 java mvn docker; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Missing required developer tool: $tool" >&2
    exit 2
  fi
done

# Never report a classpath smoke test as a working clinical workflow.
python3 scripts/check_example_workflow_evidence.py --enforce

echo "=== Java, Maven and Docker versions ==="
java -version
mvn -version
docker info --format 'Docker Server Version: {{.ServerVersion}}'

echo "=== Compile SDK modules; not a clinical validation ==="
mvn -B -ntp -fae -DskipTests install

failures=0
for app in \
  standalone-pharmacy diagnostic-laboratory radiology-center \
  pharma-manufacturing life-sciences-lims biobank clinical-research \
  outpatient-emr longitudinal-ehr hospital-his regional-hie; do
  echo "=== Independent application: $app ==="
  # Clean avoids falsely accepting stale report XML from a previous run.
  if ! mvn -B -ntp -f "examples/$app/pom.xml" clean verify -DskipITs=false; then
    echo "FAILED: Maven integration suite for $app" >&2
    failures=$((failures + 1))
    continue
  fi
  if ! python3 - "$app" <<'PY'
import pathlib
import sys
import xml.etree.ElementTree as ET

app = sys.argv[1]
directory = pathlib.Path("examples") / app / "target" / "failsafe-reports"
reports = sorted(directory.glob("TEST-*IndependentWorkflowIT.xml"))
if not reports:
    print(f"FAILED {app}: no IndependentWorkflowIT Failsafe XML", file=sys.stderr)
    sys.exit(1)
executed = 0
failed = 0
for report in reports:
    root = ET.parse(report).getroot()
    tests = int(root.attrib.get("tests", "0"))
    skipped = int(root.attrib.get("skipped", "0"))
    errors = int(root.attrib.get("errors", "0"))
    failures = int(root.attrib.get("failures", "0"))
    executed += tests - skipped
    failed += errors + failures
if executed < 1 or failed:
    print(f"FAILED {app}: executed={executed} failed/errors={failed}", file=sys.stderr)
    sys.exit(1)
print(f"EXECUTED {app}: {executed} independent workflow test(s)")
PY
  then
    failures=$((failures + 1))
  fi
done

if ((failures > 0)); then
  echo "NOT VERIFIED: $failures standalone consumer(s) failed or ran no real workflow ITs" >&2
  exit 1
fi
echo "Eleven independent workflow suites executed without reported failures."
echo "Human clinical, data protection and regulatory review still required."
