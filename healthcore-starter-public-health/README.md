# Public Health, CHW, EPI & IDSR Surveillance Starter (`healthcore-starter-public-health`)

## 1. Purpose
Supports GPS-tagged CHW households, ANC/iCCM/MUAC field visits with automatic SAM emergency referral QR codes, WHO EPI immunization schedules, HIV ART / TB DOT / Hepatitis chronic cascades, IDSR outbreak thresholds, and DHIS2 ADX/Tracker push.

## 2. Implemented Capabilities
- Spring Boot 4.0 auto-configured starter (`AutoConfiguration.imports` + `@EnableJpaRepositories(considerNestedRepositories = true)`).
- Tenant-scoped domain services, JPA entities, and secured REST/standards endpoints.
- Cross-database Liquibase schema support (PostgreSQL, Microsoft SQL Server, MySQL).

## 3. Explicitly Unsupported Capabilities
Does not include live Ministry of Health DHIS2 production credentials or telecom SMS outbreak blasting.

## 4. Domain Model
- `CaseInvestigationFormEntity` (`hc_idsr_cif_forms`)
- `ChronicCascadeEntity` (`hc_chronic_cascades`)
- `ChwHouseholdEntity` (`hc_chw_households`)
- `ChwVisitEntity` (`hc_chw_visits`)
- `ContactTracingLinkEntity` (`hc_contact_tracing_links`)
- `Dhis2MappingEntity` (`hc_dhis2_mappings`)
- `Dhis2SyncLogEntity` (`hc_dhis2_sync_logs`)
- `HomeCarePlanEntity` (`hc_home_care_plans`)
- `ImmunizationRecordEntity` (`hc_immunization_records`)
- `SurveillanceCaseEntity` (`hc_surveillance_cases`)

## 5. Database Migrations
- `db/changelog/public_health/001-public-health.xml`
- `db/changelog/public_health/002-public-health-cascades.xml`
- `db/changelog/public_health/003-dhis2-sync.xml`

## 6. REST APIs
- `POST /api/v1/public-health/dhis2/mappings`
- `GET /api/v1/public-health/dhis2/mappings`
- `POST /api/v1/public-health/dhis2/push/data-value-sets`
- `POST /api/v1/public-health/dhis2/push/tracker/{caseId}`
- `GET /api/v1/public-health/dhis2/sync-logs`
- `POST /api/v1/public-health/chw/households`
- `POST /api/v1/public-health/chw/visits`
- `POST /api/v1/public-health/home-care`
- `POST /api/v1/public-health/immunizations`
- `POST /api/v1/public-health/chronic-cascades`
- `GET /api/v1/public-health/chronic-cascades/{patientId}`
- `POST /api/v1/public-health/surveillance/cases`
- `POST /api/v1/public-health/idsr/cif`
- `POST /api/v1/public-health/surveillance/contacts`
- `GET /api/v1/public-health/surveillance/dhis2-adx`

## 7. FHIR / HL7 / Standards Mappings
- Maps internal domain identifiers using UUIDv7 and standard clinical terminologies (LOINC, SNOMED CT, ICD-10/11, RxNorm, UCUM) where applicable.
- See `docs/API_CONFORMANCE_REPORT.md` for verified FHIR R4, HL7 v2.5.1, IHE, and DICOMweb conformance boundaries.

## 8. Authorization Model
- Zero `or isAuthenticated()` fallbacks on clinical or patient-data endpoints.
- Method-level `@PreAuthorize` guards detected in this module:
- `hasAuthority('PERM_audit:export') or hasAuthority('PERM_dashboard:executive') or hasAuthority('PERM_facility:manage')`
- `hasAuthority('PERM_audit:export') or hasAuthority('PERM_facility:manage') or hasAuthority('PERM_system:admin')`
- `hasAuthority('PERM_audit:read') or hasAuthority('PERM_facility:manage')`
- `hasAuthority('PERM_chw:household:write')`
- `hasAuthority('PERM_chw:visit:write') or hasAuthority('PERM_encounter:sign')`
- `hasAuthority('PERM_chw:visit:write')`
- `hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_chw:visit:write') or hasAuthority('PERM_lab:verify')`
- `hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_chw:visit:write')`
- `hasAuthority('PERM_facility:manage') or hasAuthority('PERM_system:admin')`
- `hasAuthority('PERM_immunization:write') or hasAuthority('PERM_immunization:record')`
- `hasAuthority('PERM_patient:read') or hasAuthority('PERM_chw:visit:write')`

## 9. Tenant Scope
- All clinical and operational records are scoped by `facility_id` via `FacilityContext`, JWT `facility_id` validation, Hibernate `facilityFilter`, and PostgreSQL Row-Level Security (`PostgresRlsSessionAspect`).

## 10. Audit Events
`DHIS2_PUSH_AGGREGATE`, `DHIS2_PUSH_TRACKER`, `IDSR_REPORT_CASE`

## 11. Configuration
- Configured via standard Spring Boot properties and environment variables documented in `.env.example` and `docs/PRODUCTION_RUNBOOK.md`.
- No hardcoded production secrets; `ProductionSecretsStartupValidator` fails closed in production profiles.

## 12. Deployment Requirements
- Java 25 (JDK 25), Spring Boot 4.0, PostgreSQL 16+ (primary) or MSSQL / MySQL, and OIDC/Keycloak issuer.
- Include `com.opeshealth.healthcore:healthcore-starter-public-health:1.0.0` with `healthcore-bom`.

## 13. Failure Behavior
- Fails closed (`AccessDeniedException` / `SecurityException` / `IllegalStateException`) on missing tenant context, unauthorized access, invalid clinical state transitions, or missing mandatory safety context.
- Never returns fabricated `200 OK` responses for unsupported operations.

## 14. Clinical Validation Status
- **Status:** PRE-PRODUCTION / ENGINEERING BUILD.
- Epidemic threshold tables and MUAC clinical cutoffs require national public health program validation.

## 15. Test Evidence
- Module test classes:
- `PublicHealthSurveillanceTest.java`
- Verification command (JDK 25 CI): `mvn -B -ntp test -pl healthcore-starter-public-health -am`

## 16. Known Limitations
- Live Maven/JUnit/Testcontainers execution must be verified in JDK 25 + Docker CI.
- Refer to `docs/KNOWN_LIMITATIONS_REGISTER.md` for full platform-level caveats and go-live gates.
