# Radiology Information System (RIS) Starter (`healthcore-starter-radiology`)

## 1. Purpose
Manages durable DICOM Modality Worklist (MWL) items, Modality Performed Procedure Step (MPPS) state transitions, structured radiology reports with immutable addenda, and closed-loop critical finding verbal read-back.

## 2. Implemented Capabilities
- Spring Boot 4.0 auto-configured starter (`AutoConfiguration.imports` + `@EnableJpaRepositories(considerNestedRepositories = true)`).
- Tenant-scoped domain services, JPA entities, and secured REST/standards endpoints.
- Cross-database Liquibase schema support (PostgreSQL, Microsoft SQL Server, MySQL).

## 3. Explicitly Unsupported Capabilities
Does not store raw DICOM binary pixel arrays locally (delegates binary storage/retrieval to external PACS via DICOMweb proxy).

## 4. Domain Model
- `ModalityWorklistEntity` (`hc_ris_modality_worklist`)
- `RadiologyReportEntity` (`hc_ris_diagnostic_reports`)

## 5. Database Migrations
- `db/changelog/radiology/001-radiology-ris.xml`

## 6. REST APIs
- `POST /api/v1/ris/worklist`
- `GET /api/v1/ris/worklist`
- `PATCH /api/v1/ris/worklist/{id}/mpps`
- `POST /api/v1/ris/reports`
- `POST /api/v1/ris/reports/{reportId}/acknowledge-critical`
- `GET /api/v1/ris/reports/critical-unacknowledged`

## 7. FHIR / HL7 / Standards Mappings
- Maps internal domain identifiers using UUIDv7 and standard clinical terminologies (LOINC, SNOMED CT, ICD-10/11, RxNorm, UCUM) where applicable.
- See `docs/API_CONFORMANCE_REPORT.md` for verified FHIR R4, HL7 v2.5.1, IHE, and DICOMweb conformance boundaries.

## 8. Authorization Model
- Zero `or isAuthenticated()` fallbacks on clinical or patient-data endpoints.
- Method-level `@PreAuthorize` guards detected in this module:
- `hasAuthority('PERM_dicom:index') or hasAuthority('PERM_imaging:report:sign')`
- `hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_order:sign')`
- `hasAuthority('PERM_imaging:report:sign')`
- `hasAuthority('PERM_order:sign')`
- `hasAuthority('PERM_patient:read')`

## 9. Tenant Scope
- All clinical and operational records are scoped by `facility_id` via `FacilityContext`, JWT `facility_id` validation, Hibernate `facilityFilter`, and PostgreSQL Row-Level Security (`PostgresRlsSessionAspect`).

## 10. Audit Events
Tamper-evident clinical audit trail via `ClinicalAuditService` / `@ClinicalAudit`.

## 11. Configuration
- Configured via standard Spring Boot properties and environment variables documented in `.env.example` and `docs/PRODUCTION_RUNBOOK.md`.
- No hardcoded production secrets; `ProductionSecretsStartupValidator` fails closed in production profiles.

## 12. Deployment Requirements
- Java 25 (JDK 25), Spring Boot 4.0, PostgreSQL 16+ (primary) or MSSQL / MySQL, and OIDC/Keycloak issuer.
- Include `com.opeshealth.healthcore:healthcore-starter-radiology:1.0.0` with `healthcore-bom`.

## 13. Failure Behavior
- Fails closed (`AccessDeniedException` / `SecurityException` / `IllegalStateException`) on missing tenant context, unauthorized access, invalid clinical state transitions, or missing mandatory safety context.
- Never returns fabricated `200 OK` responses for unsupported operations.

## 14. Clinical Validation Status
- **Status:** PRE-PRODUCTION / ENGINEERING BUILD.
- Critical imaging finding communication timelines require radiology department SOP validation.

## 15. Test Evidence
- Module test classes:
- `RadiologyRisServiceTest.java`
- Verification command (JDK 25 CI): `mvn -B -ntp test -pl healthcore-starter-radiology -am`

## 16. Known Limitations
- Live Maven/JUnit/Testcontainers execution must be verified in JDK 25 + Docker CI.
- Refer to `docs/KNOWN_LIMITATIONS_REGISTER.md` for full platform-level caveats and go-live gates.
