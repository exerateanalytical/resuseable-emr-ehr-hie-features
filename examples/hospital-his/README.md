# Composable Hospital Information System (HIS) Example

Minimal standalone Spring Boot 4 + Java 25 consumer proving that OPES HealthCore modules run independently without pulling unrelated industries.

- **Installed Dependencies**: `healthcore-pack-his`
- **Excluded / Never Installed**: Pharma Manufacturing, Pharma QMS, Biobanking, Genomics Research
- **Liquibase Changelog**: `classpath:db/changelog/packs/pack-his-master.xml`

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
curl -H "X-Facility-Id: 00000000-0000-0000-0000-000000000001" http://localhost:8090/actuator/health
```
