# EMR 10-Stage Clinical Workflows Starter (`healthcore-starter-emr-clinical`)

## 1. Purpose
Orchestrates outpatient/inpatient encounters, ESI triage, LOINC vitals + RCP NEWS2 Scale 1 scoring, immutable SOAP note revisions & co-signing, CDSS/license-gated CPOE orders, ADT admission/transfer/discharge, 5-Rights barcode eMAR, I&O balance, SBAR shift handovers, medication reconciliation, referrals, and specialty packs.

## 2. Implemented Capabilities
- Spring Boot 4.0 auto-configured starter (`AutoConfiguration.imports` + `@EnableJpaRepositories(considerNestedRepositories = true)`).
- Tenant-scoped domain services, JPA entities, and secured REST/standards endpoints.
- Cross-database Liquibase schema support (PostgreSQL, Microsoft SQL Server, MySQL).

## 3. Explicitly Unsupported Capabilities
Does not claim NEWS2 Scale 2 (hypercapnic COPD), pediatric PEWS, obstetric MEOWS, or medical-device hardware barcode scanner certification.

## 4. Domain Model
- `AdmissionEntity` (`hc_admissions`)
- `AppointmentEntity` (`hc_appointments`)
- `CarePlanEntity` (`hc_care_plans`)
- `ClinicalNoteRevisionEntity` (`hc_clinical_notes`)
- `ClinicalOrderEntity` (`hc_clinical_orders`)
- `ClinicalReferralEntity` (`hc_clinical_referrals`)
- `EmarAdministrationEntity` (`hc_emar_administrations`)
- `EncounterEntity` (`hc_encounters`)
- `FluidBalanceEntity` (`hc_fluid_balance`)
- `MedReconciliationEntity` (`hc_med_reconciliations`)
- `ShiftHandoverEntity` (`hc_shift_handovers`)
- `SpecialtyRecordEntity` (`hc_specialty_records`)
- `TriageAssessmentEntity` (`hc_triage_assessments`)
- `VitalSignsEntity` (`hc_vital_signs`)
- `WardBedEntity` (`hc_ward_beds`)

## 5. Database Migrations
- `db/changelog/emr/001-emr-clinical.xml`
- `db/changelog/emr/002-emr-ward-workflows.xml`

## 6. REST APIs
- `POST /api/v1/emr/appointments`
- `POST /api/v1/emr/appointments/{appointmentId}/transition`
- `POST /api/v1/emr/encounters`
- `POST /api/v1/emr/triage`
- `POST /api/v1/emr/vitals`
- `POST /api/v1/emr/notes`
- `POST /api/v1/emr/notes/{noteId}/cosign`
- `POST /api/v1/emr/orders`
- `POST /api/v1/emr/admissions`
- `POST /api/v1/emr/admissions/{admissionId}/transfer-bed`
- `POST /api/v1/emr/emar`
- `POST /api/v1/emr/ward/fluid-balance`
- `POST /api/v1/emr/ward/shift-handover`
- `POST /api/v1/emr/admissions/{admissionId}/med-reconciliation`
- `POST /api/v1/emr/referrals`
- `POST /api/v1/emr/referrals/{referralId}/complete`
- `POST /api/v1/emr/admissions/{admissionId}/discharge`
- `POST /api/v1/emr/specialty/{specialtyCode}`

## 7. FHIR / HL7 / Standards Mappings
- Maps internal domain identifiers using UUIDv7 and standard clinical terminologies (LOINC, SNOMED CT, ICD-10/11, RxNorm, UCUM) where applicable.
- See `docs/API_CONFORMANCE_REPORT.md` for verified FHIR R4, HL7 v2.5.1, IHE, and DICOMweb conformance boundaries.

## 8. Authorization Model
- Zero `or isAuthenticated()` fallbacks on clinical or patient-data endpoints.
- Method-level `@PreAuthorize` guards detected in this module:
- `hasAuthority('PERM_emar:administer')`
- `hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_encounter:draft') or hasAuthority('PERM_triage:write')`
- `hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_encounter:draft')`
- `hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_rx:dispense')`
- `hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_specialty:dental:write') or hasAuthority('PERM_specialty:eye:write') or hasAuthority('PERM_specialty:cardiac:write') or hasAuthority('PERM_specialty:peds:write') or hasAuthority('PERM_specialty:derm:write')`
- `hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_ward:handover')`
- `hasAuthority('PERM_encounter:sign')`
- `hasAuthority('PERM_io:write') or hasAuthority('PERM_emar:administer')`
- `hasAuthority('PERM_note:cosign')`
- `hasAuthority('PERM_order:sign') or hasAuthority('PERM_rx:prescribe')`
- `hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_triage:write')`
- `hasAuthority('PERM_patient:write') or hasAuthority('PERM_self:appointment:request')`
- `hasAuthority('PERM_triage:write') or hasAuthority('PERM_encounter:sign')`
- `hasAuthority('PERM_vitals:write') or hasAuthority('PERM_encounter:sign')`
- `hasAuthority('PERM_ward:handover') or hasAuthority('PERM_encounter:sign')`

## 9. Tenant Scope
- All clinical and operational records are scoped by `facility_id` via `FacilityContext`, JWT `facility_id` validation, Hibernate `facilityFilter`, and PostgreSQL Row-Level Security (`PostgresRlsSessionAspect`).

## 10. Audit Events
`EMR_ADMINISTER_EMAR`, `EMR_ADMIT_INPATIENT`, `EMR_COSIGN_NOTE`, `EMR_DISCHARGE_PATIENT`, `EMR_PLACE_ORDER`, `EMR_SIGN_NOTE`, `EMR_START_ENCOUNTER`, `EMR_TRANSFER_BED`

## 11. Configuration
- Configured via standard Spring Boot properties and environment variables documented in `.env.example` and `docs/PRODUCTION_RUNBOOK.md`.
- No hardcoded production secrets; `ProductionSecretsStartupValidator` fails closed in production profiles.

## 12. Deployment Requirements
- Java 25 (JDK 25), Spring Boot 4.0, PostgreSQL 16+ (primary) or MSSQL / MySQL, and OIDC/Keycloak issuer.
- Include `com.opeshealth.healthcore:healthcore-starter-emr-clinical:1.0.0` with `healthcore-bom`.

## 13. Failure Behavior
- Fails closed (`AccessDeniedException` / `SecurityException` / `IllegalStateException`) on missing tenant context, unauthorized access, invalid clinical state transitions, or missing mandatory safety context.
- Never returns fabricated `200 OK` responses for unsupported operations.

## 14. Clinical Validation Status
- **Status:** PRE-PRODUCTION / ENGINEERING BUILD.
- Clinical validation pending institutional clinical safety officer sign-off (triage, NEWS2, CDSS, eMAR, med reconciliation).

## 15. Test Evidence
- Module test classes:
- `EmrClinicalSafetyTest.java`
- Verification command (JDK 25 CI): `mvn -B -ntp test -pl healthcore-starter-emr-clinical -am`

## 16. Known Limitations
- Live Maven/JUnit/Testcontainers execution must be verified in JDK 25 + Docker CI.
- Refer to `docs/KNOWN_LIMITATIONS_REGISTER.md` for full platform-level caveats and go-live gates.
