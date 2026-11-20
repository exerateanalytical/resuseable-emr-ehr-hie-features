#!/usr/bin/env python3
"""Structural audit only: never interpret as runtime or clinical validation."""
from __future__ import annotations
import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
EXAMPLES = ["standalone-pharmacy","diagnostic-laboratory","radiology-center","pharma-manufacturing","life-sciences-lims","biobank","clinical-research","outpatient-emr","longitudinal-ehr","hospital-his","regional-hie"]

def audit_example(name: str) -> tuple[bool, str]:
    test_dir = ROOT / "examples" / name / "src" / "test"
    if not test_dir.is_dir():
        return False, "no tests directory"
    files = list(test_dir.rglob("*.java"))
    candidates = [
        path for path in files
        if path.name.endswith("IT.java")
        or "IntegrationTest" in path.name
        or "WorkflowE2ETest" in path.name
    ]
    for path in candidates:
        source = path.read_text(encoding="utf-8")
        has_test = "@Test" in source
        tagged = 'workflow-it' in source
        container = ("PostgreSQLContainer" in source or "@Testcontainers" in source
                     or "jdbc:postgresql:" in source)
        assertion = bool(re.search(r"\\bassert(?:Equals|True|False|Throws|NotNull|NotEquals|All)\\s*\\(", source))
        # Neither static presence nor a mocked core service proves DB behavior.
        if has_test and tagged and container and assertion:
            return True, str(path.relative_to(ROOT))
    return False, "missing tagged PostgreSQL workflow IT (existing classpath smoke test is insufficient)"

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--enforce", action="store_true", help="Fail until all 11 examples have structural workflow evidence")
    args = parser.parse_args()
    missing = 0
    for name in EXAMPLES:
        ok, message = audit_example(name)
        missing += not ok
        print(f"{'FOUND' if ok else 'MISSING'} examples/{name}: {message}")
    print(f"Structural gate: {len(EXAMPLES)-missing}/{len(EXAMPLES)} (NOT a runtime pass)")
    return 1 if args.enforce and missing else 0

if __name__ == "__main__":
    sys.exit(main())
