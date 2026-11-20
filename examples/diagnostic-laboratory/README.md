# Standalone Diagnostic Laboratory Example (ISO 15189-Aligned, Zero Radiology)

Minimal standalone Spring Boot 4 + Java 25 consumer proving that OPES HealthCore modules run independently without pulling unrelated industries.

- **Installed Dependencies**: `healthcore-starter-diagnostics-core, healthcore-starter-lis-diagnostics`
- **Excluded / Never Installed**: Radiology/PACS, EMR, Inpatient, HIS
- **Liquibase Changelog**: `classpath:db/changelog/diagnostics_core/001-diagnostics-core.xml`

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
curl -X POST http://localhost:8082/api/v1/diagnostics-core/orders -H "Content-Type: application/json" -H "X-Facility-Id: 00000000-0000-0000-0000-000000000001" -d '{"accessionNumber":"LAB-001","modalityDomain":"LABORATORY","catalogCode":"LOINC-2345-7","subjectRef":"CLIENT-1","tatSlaMinutes":120}'
```
