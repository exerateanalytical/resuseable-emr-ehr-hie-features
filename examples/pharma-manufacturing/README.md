# Pharmaceutical Manufacturing, QMS & Serialization Example (Zero Clinical/EMR/MPI)

Minimal standalone Spring Boot 4 + Java 25 consumer proving that OPES HealthCore modules run independently without pulling unrelated industries.

- **Installed Dependencies**: `healthcore-pack-pharma-manufacturing`
- **Excluded / Never Installed**: MPI, EMR, Hospital Ops, Inpatient, Surgery, Mortuary
- **Liquibase Changelog**: `classpath:db/changelog/packs/pack-pharma-manufacturing-master.xml`

## Quick Start
```bash
# 1. Publish HealthCore BOM & starters locally (once from repository root)
mvn install -DskipTests

# 2. Start PostgreSQL
docker compose up -d

# 3. Run this standalone example
mvn spring-boot:run
```

## Smoke Test
```bash
curl -H "X-Facility-Id: 00000000-0000-0000-0000-000000000001" http://localhost:8084/api/v1/pharma/manufacturing/batches
```
