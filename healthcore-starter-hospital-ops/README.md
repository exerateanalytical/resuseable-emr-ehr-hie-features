# Hospital Operations, Workforce Credentials & Biomedical Cold Chain Starter (`healthcore-starter-hospital-ops`)

## 1. Purpose
Implements WorkforceCredentialSpi practitioner license verification, shift rosters, biomedical equipment & cold-chain MQTT temperature telemetry monitoring, and CRVS/mortuary operational workflows.

## 2. Implemented Capabilities
- Spring Boot 4.0 auto-configured starter (`AutoConfiguration.imports` + `@EnableJpaRepositories(considerNestedRepositories = true)`).
- Tenant-scoped domain services, JPA entities, and secured REST/standards endpoints.
- Cross-database Liquibase schema support (PostgreSQL, Microsoft SQL Server, MySQL).

## 3. Explicitly Unsupported Capabilities
Does not include live external medical licensing board APIs or physical IoT sensor hardware calibration.

## 4. Domain Model
- `AnesthesiaPacuLogEntity` (`hc_anesthesia_pacu_logs`)
- `BiomedMaintenanceEntity` (`hc_biomed_maintenance`)
- `BloodComponentUnitEntity` (`hc_blood_units`)
- `BloodDonorEntity` (`hc_blood_donors`)
- `ColdChainTelemetryEntity` (`hc_cold_chain_telemetry`)
- `CrvsBirthDeathEntity` (`hc_crvs_events`)
- `PractitionerCredentialEntity` (`hc_practitioner_credentials`)
- `SurgicalScheduleEntity` (`hc_surgical_schedules`)
- `TelemedicineSessionEntity` (`hc_telemedicine_sessions`)
- `TransfusionReactionEntity` (`hc_transfusion_reactions`)
- `WorkforceRosterEntity` (`hc_workforce_rosters`)

## 5. Database Migrations
- `db/changelog/hospital_ops/001-hospital-ops.xml`
- `db/changelog/hospital_ops/002-hospital-ops-expanded.xml`

## 6. REST APIs
- `POST /api/v1/ops/blood/donors`
- `POST /api/v1/ops/blood/units`
- `POST /api/v1/ops/blood/units/{id}/transfuse`
- `POST /api/v1/ops/blood/reactions`
- `POST /api/v1/ops/ot/surgeries`
- `POST /api/v1/ops/ot/surgeries/{id}/complete`
- `POST /api/v1/ops/ot/anesthesia-pacu`
- `POST /api/v1/ops/telemedicine/sessions`
- `POST /api/v1/ops/cold-chain/telemetry`
- `POST /api/v1/ops/cold-chain/mqtt-webhook`
- `POST /api/v1/ops/biomed/maintenance`
- `POST /api/v1/ops/crvs/events`
- `POST /api/v1/ops/crvs/events/{id}/mortuary-release`
- `POST /api/v1/ops/workforce/credentials`
- `POST /api/v1/ops/workforce/rosters`
- `GET /api/v1/ops/workforce/rosters`
- `GET /api/v1/ops/deidentify`

## 7. FHIR / HL7 / Standards Mappings
- Maps internal domain identifiers using UUIDv7 and standard clinical terminologies (LOINC, SNOMED CT, ICD-10/11, RxNorm, UCUM) where applicable.
- See `docs/API_CONFORMANCE_REPORT.md` for verified FHIR R4, HL7 v2.5.1, IHE, and DICOMweb conformance boundaries.

## 8. Authorization Model
- Zero `or isAuthenticated()` fallbacks on clinical or patient-data endpoints.
- Method-level `@PreAuthorize` guards detected in this module:
- `hasAuthority('PERM_audit:export') or hasAuthority('PERM_system:admin')`
- `hasAuthority('PERM_bloodbank:crossmatch') or hasAuthority('PERM_emar:administer')`
- `hasAuthority('PERM_bloodbank:crossmatch') or hasAuthority('PERM_lab:verify')`
- `hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_encounter:draft') or hasAuthority('PERM_self:appointment:request')`
- `hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_facility:manage')`
- `hasAuthority('PERM_facility:manage') or hasAuthority('PERM_encounter:sign')`
- `hasAuthority('PERM_facility:manage') or hasAuthority('PERM_system:admin')`
- `hasAuthority('PERM_ot:schedule') or hasAuthority('PERM_encounter:sign')`
- `hasAuthority('PERM_ot:schedule')`
- `hasAuthority('PERM_staff:onboard') or hasAuthority('PERM_facility:manage') or hasAuthority('PERM_encounter:sign')`
- `hasAuthority('PERM_staff:onboard') or hasAuthority('PERM_facility:manage')`

## 9. Tenant Scope
- All clinical and operational records are scoped by `facility_id` via `FacilityContext`, JWT `facility_id` validation, Hibernate `facilityFilter`, and PostgreSQL Row-Level Security (`PostgresRlsSessionAspect`).

## 10. Audit Events
`BLOOD_BANK_HEMOVIGILANCE`, `BLOOD_BANK_TRANSFUSE`, `CRVS_MORTUARY_RELEASE`

## 11. Configuration
- Configured via standard Spring Boot properties and environment variables documented in `.env.example` and `docs/PRODUCTION_RUNBOOK.md`.
- No hardcoded production secrets; `ProductionSecretsStartupValidator` fails closed in production profiles.

## 12. Deployment Requirements
- Java 25 (JDK 25), Spring Boot 4.0, PostgreSQL 16+ (primary) or MSSQL / MySQL, and OIDC/Keycloak issuer.
- Include `com.opeshealth.healthcore:healthcore-starter-hospital-ops:1.0.0` with `healthcore-bom`.

## 13. Failure Behavior
- Fails closed (`AccessDeniedException` / `SecurityException` / `IllegalStateException`) on missing tenant context, unauthorized access, invalid clinical state transitions, or missing mandatory safety context.
- Never returns fabricated `200 OK` responses for unsupported operations.

## 14. Clinical Validation Status
- **Status:** PRE-PRODUCTION / ENGINEERING BUILD.
- Cold-chain excursion thresholds and credential grace policies require hospital operations & QA sign-off.

## 15. Test Evidence
- Module test classes:
- Covered via `healthcore-quickstart-host` integration/safety suites.
- Verification command (JDK 25 CI): `mvn -B -ntp test -pl healthcore-starter-hospital-ops -am`

## 16. Known Limitations
- Live Maven/JUnit/Testcontainers execution must be verified in JDK 25 + Docker CI.
- Refer to `docs/KNOWN_LIMITATIONS_REGISTER.md` for full platform-level caveats and go-live gates.
