#!/usr/bin/env bash
# Repeatable Java 25 verification for the entire OPES HealthCore Maven reactor.
set -euo pipefail

MODE="${1:-unit}"
if [[ "$MODE" != "compile" && "$MODE" != "unit" && "$MODE" != "full" ]]; then
  echo "Usage: bash scripts/verify-sdk-java25.sh [compile|unit|full]" >&2
  exit 2
fi

java -version
mvn -version

if [[ "$MODE" == "compile" ]]; then
  mvn -B -ntp -DskipTests clean compile
elif [[ "$MODE" == "unit" ]]; then
  mvn -B -ntp -DskipITs=true clean verify
else
  if ! docker info >/dev/null 2>&1; then
    echo "Full verification needs Docker (Testcontainers): check Docker Desktop / Docker socket." >&2
    exit 1
  fi
  mvn -B -ntp clean verify
  mvn -B -ntp -pl healthcore-quickstart-host -am verify \
    -Dit.test=MultiDbTestcontainersIT,PatientJourneyPostgresIT \
    -DfailIfNoTests=false \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dfailsafe.failIfNoSpecifiedTests=false
fi
