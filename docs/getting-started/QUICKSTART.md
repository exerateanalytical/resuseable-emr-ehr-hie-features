# OPES HealthCore — Developer Quickstart (Modes A, B & C)

**Artifact Group**: `com.opeshealth.healthcore` | **Version**: `1.0.0` | **Runtime**: Java 25, Spring Boot 4.0, Liquibase (PostgreSQL 16+ / MSSQL 2022+ / MySQL 8.4+)

OPES HealthCore is a reusable, modular healthcare and life sciences SDK. You never need to install a monolithic hospital system to build a retail pharmacy, a diagnostic laboratory, a GMP manufacturing MES/QMS, a biobank, or an outpatient clinic.

---

## Step 1: Import the OPES HealthCore BOM

Every consuming application starts by importing `healthcore-bom` so all HealthCore starters and packs resolve compatible versions automatically.

### Maven (`pom.xml`)
```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.opeshealth.healthcore</groupId>
            <artifactId>healthcore-bom</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### Gradle (`build.gradle.kts`)
```kotlin
dependencies {
    implementation(platform("com.opeshealth.healthcore:healthcore-bom:1.0.0"))
}
```

---

## Step 2: Choose Your Installation Mode

### Mode A — Individual Module Installation (Minimal Footprint)
Install **only** the single domain starter you need. Example: a standalone ISO 20387 Biobank (`examples/biobank`) with zero EMR, MPI, Pharmacy, or HIS tables:

```xml
<dependency>
    <groupId>com.opeshealth.healthcore</groupId>
    <artifactId>healthcore-starter-biobanking</artifactId>
</dependency>
```

Create `src/main/resources/db/changelog/db.changelog-master.xml` including portable database type properties (`uuid_type`, `ts_type`, `timestamp_type`, `clob_type`, `bool_type`) plus `000-persistence-master.xml`, `001-security-audit.xml`, and `biobanking/001-biobanking.xml`. See `examples/biobank/src/main/resources/db/changelog/db.changelog-master.xml`.

### Mode B — Industry Capability Pack Installation (Recommended for Turnkey Verticals)
Install one of the 8 pre-composed, isolation-verified Industry Capability Packs:

| Pack ArtifactId | Industry Domain | Liquibase Master Changelog | Standalone Example |
|---|---|---|---|
| `healthcore-pack-pharmacy` | Independent, Retail, Chain & Wholesale Pharmacy | `classpath:db/changelog/packs/pack-pharmacy-master.xml` | `examples/standalone-pharmacy` |
| `healthcore-pack-diagnostics` | Diagnostic Labs, Collection Networks & Radiology | `classpath:db/changelog/packs/pack-diagnostics-master.xml` | `examples/diagnostic-laboratory`, `examples/radiology-center` |
| `healthcore-pack-pharma-manufacturing` | Pharma GMP Manufacturing, QMS & GS1 Serialization | `classpath:db/changelog/packs/pack-pharma-manufacturing-master.xml` | `examples/pharma-manufacturing` |
| `healthcore-pack-life-sciences` | Research LIMS, Biobanking, Genomics & Clinical Trials | `classpath:db/changelog/packs/pack-life-sciences-master.xml` | `examples/life-sciences-lims`, `examples/biobank`, `examples/clinical-research` |
| `healthcore-pack-emr` | Outpatient Clinic & Specialist Practice EMR | `classpath:db/changelog/packs/pack-emr-master.xml` | `examples/outpatient-emr` |
| `healthcore-pack-ehr` | Multi-Facility Longitudinal EHR & Portal | `classpath:db/changelog/packs/pack-ehr-master.xml` | `examples/longitudinal-ehr` |
| `healthcore-pack-his` | Composable Hospital Information System (5 profiles) | `classpath:db/changelog/packs/pack-his-master.xml` | `examples/hospital-his` |
| `healthcore-pack-hie` | Regional / National Health Information Exchange | `classpath:db/changelog/packs/pack-hie-master.xml` | `examples/regional-hie` |

```xml
<dependency>
    <groupId>com.opeshealth.healthcore</groupId>
    <artifactId>healthcore-pack-pharmacy</artifactId>
</dependency>
```

### Mode C — Custom Multi-Module Composition
Combine individual starters across packs for hybrid organizations (e.g., an independent Radiology Center combining `healthcore-starter-diagnostics-core` + `healthcore-starter-radiology`, or an Outpatient Clinic adding `healthcore-starter-telemedicine` + `healthcore-starter-uhc-billing`).

> **Conflict Rule**: Never mix `healthcore-starter-pharmacy-commerce` (standalone retail/chain pharmacy) and `healthcore-starter-pharmacy-lis` (inpatient hospital pharmacy) in the same Spring Boot JVM without explicit namespace isolation. `CapabilityPackManifestContract.detectDependencyConflicts(...)` validates this automatically.

---

## Step 3: Configure Database, Liquibase & Multi-Tenant Security

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/healthcore_app}
    username: ${DB_USER:healthcore}
    password: ${DB_PASSWORD:change_me_local_only}
  jpa:
    hibernate:
      ddl-auto: validate
  liquibase:
    enabled: true
    change-log: classpath:db/changelog/packs/pack-pharmacy-master.xml
```

Every authenticated HTTP request must supply:
- `Authorization: Bearer <JWT>` (validated against your OIDC/JWKS issuer or configured HMAC/RSA verifier)
- `X-Facility-Id: <UUIDv7>` (binds `FacilityContext` and Hibernate `@Filter("facilityFilter")` automatically so cross-facility data access is blocked at persistence level)

---

## Step 4: Verify Your Installation

1. Start PostgreSQL (or MSSQL 2022 / MySQL 8.4) using the example's `docker-compose.yml`:
   ```bash
   cd examples/standalone-pharmacy
   docker compose up -d
   ```
2. Run the minimal Spring Boot app and smoke test:
   ```bash
   mvn spring-boot:run
   ./scripts/smoke-test.sh
   ```
3. Import `api/postman-collection.json` and synthetic records from `seed/synthetic-dataset.json` to exercise persisted workflows end-to-end.
