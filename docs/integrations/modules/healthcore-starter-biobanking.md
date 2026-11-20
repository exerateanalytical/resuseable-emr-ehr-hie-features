# ISO 20387 Biobanking & Biospecimen Starter — 20-Section Integration Guide

**Artifact**: `com.opeshealth.healthcore:healthcore-starter-biobanking:1.0.0` | **Parent Pack**: Pack 04 (`healthcore-pack-life-sciences`)

## 1. Purpose & Business Scope
Provides standalone, hospital-free Spring Boot auto-configuration, domain entities, transactional services, Liquibase migrations, and REST APIs for **ISO 20387 Biobanking & Biospecimen Starter**.

## 2. Target Audience & Non-Goals
Intended for backend Java 25 / Spring Boot 4.0 engineering teams building vertical applications. Does **not** require installing EMR, MPI, or Hospital HIS modules.

## 3. Maven & Gradle Installation
```xml
<dependency>
    <groupId>com.opeshealth.healthcore</groupId>
    <artifactId>healthcore-starter-biobanking</artifactId>
</dependency>
```
*(Import `com.opeshealth.healthcore:healthcore-bom:1.0.0` in `<dependencyManagement>`.)*

## 4. Transitive Dependencies & Isolation Guarantee
Depends only on `healthcore-persistence-core` and `healthcore-security-audit` (plus `healthcore-starter-terminology-cdss` for pharmacy-commerce). Verified by `CapabilityPackIsolationTest` to pull zero unrelated HIS/EMR/MPI starters.

## 5. Java Package & Auto-Configuration
- Root package: `com.opeshealth.healthcore.biobanking`
- Registered via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

## 6. Database Schema & Liquibase Changelogs
- Changelogs: `classpath:db/changelog/biobanking/001-biobanking.xml`
- Tables: `hc_bio_aliquots`, `hc_bio_chain_events`
- Portable across PostgreSQL 16+, Microsoft SQL Server 2022+, MySQL 8.4+, and H2.

## 7. Required Configuration Properties & Environment Variables
Set `DB_URL`, `DB_USER`, `DB_PASSWORD`, `spring.jpa.hibernate.ddl-auto=validate`, and `spring.liquibase.change-log` (either the parent pack master changelog or a Mode A master changelog defining `uuid_type`, `ts_type`, `timestamp_type`, `clob_type`, `bool_type`).

## 8. Multi-Tenant & Facility Isolation
Every JPA entity declares `@Filter(name = "facilityFilter", condition = "facility_id = :facilityId")`. Clients must supply `X-Facility-Id: <UUIDv7>` on every request.

## 9. Authentication, RBAC Roles & Permissions
Endpoints enforce Spring Security `@PreAuthorize` with permissions: `PERM_lab:specimen:manage`, `PERM_lab:verify`, `PERM_facility:manage`.

## 10. Core Workflows & Business Rules
Cryogenic storage hierarchy (Freezer/Rack/Box/Slot), Aliquot genealogy & parent-child splits, Freeze-thaw cycle tracking, Donor consent revocation hard-blocking downstream aliquot release. See `docs/workflows/WORKFLOW_STATE_MACHINES.md` for full state transition diagrams and guard rules.

## 11. REST API Reference
Base path: `/api/v1/lifesciences/biobank`. Import `examples/biobank/api/postman-collection.json` for ready-to-run HTTP requests.

## 12. Domain Events & Audit Trail
Every state-changing operation is annotated with `@ClinicalAudit` and persists tamper-evident audit metadata via `healthcore-security-audit`.

## 13. Service Provider Interfaces (SPIs) & Extension Points
Customizable Spring beans: `ElectronicSignatureSpi`, `AuditLedgerSpi`. Register your own `@Bean` implementing the SPI to override default behavior without forking HealthCore.

## 14. External Application Integration Patterns
Supports in-process Spring Boot embedding, REST microservice gateway integration, and B2B webhook/API-key consumption (see `docs/integrations/EXTERNAL_APPLICATIONS_INTEGRATION_GUIDE.md`).

## 15. Security, Privacy & Data Integrity Controls
Enforces maker-checker dual verification via `ElectronicSignatureSpi` (SHA-256 tamper digests), optimistic locking (`@Version`), and strict tenant scoping.

## 16. Standards Applicability & Honest Boundaries
ISO 20387:2018 technical enablers (does not confer biobank accreditation). Technical controls in software do not by themselves confer regulatory accreditation or certification.

## 17. Synthetic Seed Data & Local Verification
Use `examples/biobank/seed/synthetic-dataset.json` (100% synthetic, zero PII), `examples/biobank/docker-compose.yml`, and `examples/biobank/scripts/smoke-test.sh`.

## 18. Observability, Health Checks & Metrics
Exposes `/actuator/health`, `/actuator/info`, and `/actuator/prometheus` via Spring Boot Actuator.

## 19. Troubleshooting & Common Pitfalls
See `docs/operations/VERSIONING_UPGRADES_AND_CONTRIBUTION.md` for Liquibase property resolution, `FacilityContext` binding, and maker-checker diagnostics.

## 20. Versioning & Upgrade Path
Follows `healthcore-bom` semantic versioning (`1.0.0`). Future schema changes ship exclusively as additive `00N-*.xml` Liquibase files.
