# OPES HealthCore — Versioning, Upgrades, Troubleshooting & Contribution Guide

This guide defines SDK lifecycle policies, semantic versioning compatibility guarantees, database migration upgrade rules, troubleshooting playbooks, and contribution standards (Directive Section 11).

---

## 1. Semantic Versioning & Compatibility Guarantees

All 58 modules in `com.opeshealth.healthcore` are versioned in lockstep via `healthcore-bom` (`MAJOR.MINOR.PATCH`, currently `1.0.0`):

- **PATCH (`1.0.x`)**: Security fixes, bug fixes, and non-breaking Liquibase additive indexes. Drop-in binary & API compatible.
- **MINOR (`1.x.0`)**: Additive REST endpoints, additive optional JSON fields, additive Liquibase changesets (`002-*.xml`, `003-*.xml`), and new SPI default methods. Zero breaking changes to existing Java signatures or database columns.
- **MAJOR (`x.0.0`)**: Breaking API or schema changes; always accompanied by a documented migration guide and deprecation notice in the prior minor release.
- **Dependency Conflict Guard**: `CapabilityPackManifestContract.detectDependencyConflicts(...)` rejects incompatible major versions or conflicting module pairs (e.g., `healthcore-starter-pharmacy-commerce` + `healthcore-starter-pharmacy-lis`) at startup or CI validation.

---

## 2. Database Schema Upgrade Policy (Liquibase)

1. **Never edit shipped changesets**: Existing Liquibase changesets (`001-*.xml`) are immutable once released so checksums (`DATABASECHANGELOG.MD5SUM`) remain valid across PostgreSQL 16+, MSSQL 2022+, and MySQL 8.4+.
2. **Additive migrations only**: New columns/tables are always shipped as sequential files (e.g., `002-pharmacy-commerce-workflows.xml`, `002-diagnostics-core-workflows.xml`, `002-pharma-qms-equipment-stability.xml`, `002-clinical-research-ip.xml`) and registered in their pack's `pack-*-master.xml`.
3. **Pre-upgrade backup & dry-run**: Before upgrading in production, run `mvn liquibase:updateSQL` against a staging snapshot, verify zero destructive DDL, and take a point-in-time database backup.

---

## 3. Troubleshooting Runbook

| Symptom | Root Cause | Resolution |
|---|---|---|
| `AccessDeniedException: FacilityContext is required` | Request missing `X-Facility-Id` header or background job running outside tenant scope | Supply a valid UUID in `X-Facility-Id` or wrap async jobs in `FacilityContext.runWithFacility(uuid, ...)` |
| `SecurityException: Maker-checker violation` | Same user ID submitted as both maker/author/analyst and approver/verifier | Use a distinct authenticated user identity for the second-person QA/pathologist/pharmacist sign-off |
| `Liquibase property ${uuid_type} unresolved` | Consumer pointed `spring.liquibase.change-log` directly at a leaf `001-*.xml` instead of a master changelog | Point `change-log` to `classpath:db/changelog/packs/pack-*-master.xml` (Mode B) or your Mode A `db.changelog-master.xml` defining `uuid_type`/`ts_type`/`clob_type`/`bool_type` |
| `IllegalStateException: GMP calibration gate blocked` | Production equipment asset has expired `calibrationDueDate` or status `OUT_OF_SERVICE` | Record fresh metrology calibration via `POST /api/v1/pharma/qms/equipment` |
| `IllegalStateException: Database lock blocked (open queries)` | Clinical research eCRF still has `openQueryCount > 0` | Resolve all open queries via `resolveDataQuery(...)` before invoking PI e-signed `signAndLockEcrf(...)` |
| `IllegalStateException: Cannot release result while IQC failed` | Latest Westgard IQC run on analyzer failed | Run passing IQC control (`POST /api/v1/diagnostics/core/iqc`) prior to result release |

---

## 4. Contribution Guidelines

1. **Pure Java 25 / Spring Boot 4.0**: Keep server modules pure Java 25 with zero Node/TypeScript build dependencies.
2. **Strict Pack Isolation**: Every new starter must declare only its minimal required dependencies. Never introduce transitive links from Pharmacy/Diagnostics/Pharma/Life Sciences into `emr-clinical`, `mpi`, or `hospital-ops`. Verify with `CapabilityPackIsolationTest`.
3. **Portable Liquibase**: Always use `${uuid_type}`, `${ts_type}`/`${timestamp_type}`, `${clob_type}`, and `${bool_type}` so migrations succeed identically on PostgreSQL, MSSQL, MySQL, and H2.
4. **Security & Audit by Default**: Annotate all state-changing service methods with `@ClinicalAudit`, enforce `@Filter(name = "facilityFilter")` on every JPA entity, and enforce `@PreAuthorize` on every controller endpoint.
5. **Documentation Parity**: Every new module must include a `README.md`, a 20-section integration guide under `docs/integrations/modules/`, unit/contract tests, and an entry in `docs/modules/MODULE_CATALOG.md`.
