# Emergency Department & Inpatient Nursing Starter (`healthcore-starter-emergency-nursing`)

## 1. Purpose
Provides ESI 1–5 ED tracking board, disposition workflows, safe nurse-to-patient ratio assignment guards, deterministic NEWS2 observation escalation, and a 5-gate inpatient discharge readiness checklist.

## 2. Implemented Capabilities
- Spring Boot 4.0 auto-configured starter (`AutoConfiguration.imports` + `@EnableJpaRepositories(considerNestedRepositories = true)`).
- Tenant-scoped domain services, JPA entities, and secured REST/standards endpoints.
- Cross-database Liquibase schema support (PostgreSQL, Microsoft SQL Server, MySQL).

## 3. Explicitly Unsupported Capabilities
Does not provide continuous bedside ICU telemetry waveform streaming or pediatric/obstetric early-warning scales.

## 4. Domain Model
- `DischargeReadinessEntity` (`hc_discharge_readiness`)
- `EmergencyVisitEntity` (`hc_ed_visits`)
- `News2ObservationEntity` (`hc_nursing_news2_scores`)
- `NursingAssignmentEntity` (`hc_nursing_assignments`)

## 5. Database Migrations
- `db/changelog/emergency_nursing/001-emergency-nursing.xml`

## 6. REST APIs
- `POST /api/v1/emergency-nursing/ed/triage`
- `GET /api/v1/emergency-nursing/ed/board`
- `POST /api/v1/emergency-nursing/ed/{visitId}/disposition`
- `POST /api/v1/emergency-nursing/nursing/assignments`
- `POST /api/v1/emergency-nursing/nursing/news2`
- `GET /api/v1/emergency-nursing/nursing/escalations`
- `PUT /api/v1/emergency-nursing/nursing/discharge-readiness`

## 7. FHIR / HL7 / Standards Mappings
- Maps internal domain identifiers using UUIDv7 and standard clinical terminologies (LOINC, SNOMED CT, ICD-10/11, RxNorm, UCUM) where applicable.
- See `docs/API_CONFORMANCE_REPORT.md` for verified FHIR R4, HL7 v2.5.1, IHE, and DICOMweb conformance boundaries.

## 8. Authorization Model
- Zero `or isAuthenticated()` fallbacks on clinical or patient-data endpoints.
- Method-level `@PreAuthorize` guards detected in this module:
- `hasAuthority('PERM_encounter:sign')`
- `hasAuthority('PERM_facility:manage') or hasAuthority('PERM_patient:write')`
- `hasAuthority('PERM_patient:read')`
- `hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:sign')`
- `hasAuthority('PERM_patient:write')`

## 9. Tenant Scope
- All clinical and operational records are scoped by `facility_id` via `FacilityContext`, JWT `facility_id` validation, Hibernate `facilityFilter`, and PostgreSQL Row-Level Security (`PostgresRlsSessionAspect`).

## 10. Audit Events
Tamper-evident clinical audit trail via `ClinicalAuditService` / `@ClinicalAudit`.

## 11. Configuration
- Configured via standard Spring Boot properties and environment variables documented in `.env.example` and `docs/PRODUCTION_RUNBOOK.md`.
- No hardcoded production secrets; `ProductionSecretsStartupValidator` fails closed in production profiles.

## 12. Deployment Requirements
- Java 25 (JDK 25), Spring Boot 4.0, PostgreSQL 16+ (primary) or MSSQL / MySQL, and OIDC/Keycloak issuer.
- Include `com.opeshealth.healthcore:healthcore-starter-emergency-nursing:1.0.0` with `healthcore-bom`.

## 13. Failure Behavior
- Fails closed (`AccessDeniedException` / `SecurityException` / `IllegalStateException`) on missing tenant context, unauthorized access, invalid clinical state transitions, or missing mandatory safety context.
- Never returns fabricated `200 OK` responses for unsupported operations.

## 14. Clinical Validation Status
- **Status:** PRE-PRODUCTION / ENGINEERING BUILD.
- ESI triage rules, NEWS2 escalation thresholds, and nurse-to-patient ratios require clinical governance validation.

## 15. Test Evidence
- Module test classes:
- `EmergencyNursingServiceTest.java`
- Verification command (JDK 25 CI): `mvn -B -ntp test -pl healthcore-starter-emergency-nursing -am`

## 16. Known Limitations
- Live Maven/JUnit/Testcontainers execution must be verified in JDK 25 + Docker CI.
- Refer to `docs/KNOWN_LIMITATIONS_REGISTER.md` for full platform-level caveats and go-live gates.
