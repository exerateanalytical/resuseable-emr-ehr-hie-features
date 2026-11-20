# Hospital Analytics & Clinical Quality Measures (CQM) Starter (`healthcore-starter-analytics`)

## 1. Purpose
Delivers live tenant-scoped operational KPIs (occupancy, lab turnaround, claims status), CQM numerator/denominator lineage tracking, k-anonymity (k >= 5) cell suppression, and 4-eyes cohort export approval.

## 2. Implemented Capabilities
- Spring Boot 4.0 auto-configured starter (`AutoConfiguration.imports` + `@EnableJpaRepositories(considerNestedRepositories = true)`).
- Tenant-scoped domain services, JPA entities, and secured REST/standards endpoints.
- Cross-database Liquibase schema support (PostgreSQL, Microsoft SQL Server, MySQL).

## 3. Explicitly Unsupported Capabilities
Does not provide an external BI warehouse/OLAP engine or automated regulatory quality reporting submission.

## 4. Domain Model
- `ClinicalQualityMeasureEntity` (`hc_analytics_quality_measures`)
- `CohortExportRequestEntity` (`hc_analytics_cohort_exports`)

## 5. Database Migrations
- `db/changelog/analytics/001-analytics-quality.xml`

## 6. REST APIs
- `GET /api/v1/analytics/kpis/operational`
- `POST /api/v1/analytics/quality-measures`
- `GET /api/v1/analytics/quality-measures`
- `POST /api/v1/analytics/cohort-exports`
- `POST /api/v1/analytics/cohort-exports/{exportId}/approve`
- `GET /api/v1/analytics/cohort-exports`

## 7. FHIR / HL7 / Standards Mappings
- Maps internal domain identifiers using UUIDv7 and standard clinical terminologies (LOINC, SNOMED CT, ICD-10/11, RxNorm, UCUM) where applicable.
- See `docs/API_CONFORMANCE_REPORT.md` for verified FHIR R4, HL7 v2.5.1, IHE, and DICOMweb conformance boundaries.

## 8. Authorization Model
- Zero `or isAuthenticated()` fallbacks on clinical or patient-data endpoints.
- Method-level `@PreAuthorize` guards detected in this module:
- `hasAuthority('PERM_facility:manage') or hasAuthority('PERM_audit:read')`
- `hasAuthority('PERM_facility:manage')`
- `hasAuthority('PERM_system:admin') or hasAuthority('PERM_facility:manage')`

## 9. Tenant Scope
- All clinical and operational records are scoped by `facility_id` via `FacilityContext`, JWT `facility_id` validation, Hibernate `facilityFilter`, and PostgreSQL Row-Level Security (`PostgresRlsSessionAspect`).

## 10. Audit Events
Tamper-evident clinical audit trail via `ClinicalAuditService` / `@ClinicalAudit`.

## 11. Configuration
- Configured via standard Spring Boot properties and environment variables documented in `.env.example` and `docs/PRODUCTION_RUNBOOK.md`.
- No hardcoded production secrets; `ProductionSecretsStartupValidator` fails closed in production profiles.

## 12. Deployment Requirements
- Java 25 (JDK 25), Spring Boot 4.0, PostgreSQL 16+ (primary) or MSSQL / MySQL, and OIDC/Keycloak issuer.
- Include `com.opeshealth.healthcore:healthcore-starter-analytics:1.0.0` with `healthcore-bom`.

## 13. Failure Behavior
- Fails closed (`AccessDeniedException` / `SecurityException` / `IllegalStateException`) on missing tenant context, unauthorized access, invalid clinical state transitions, or missing mandatory safety context.
- Never returns fabricated `200 OK` responses for unsupported operations.

## 14. Clinical Validation Status
- **Status:** PRE-PRODUCTION / ENGINEERING BUILD.
- CQM clinical measure definitions and de-identification thresholds require quality & privacy officer sign-off.

## 15. Test Evidence
- Module test classes:
- `HospitalAnalyticsServiceTest.java`
- Verification command (JDK 25 CI): `mvn -B -ntp test -pl healthcore-starter-analytics -am`

## 16. Known Limitations
- Live Maven/JUnit/Testcontainers execution must be verified in JDK 25 + Docker CI.
- Refer to `docs/KNOWN_LIMITATIONS_REGISTER.md` for full platform-level caveats and go-live gates.
