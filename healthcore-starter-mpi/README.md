# Master Patient Index (MPI) Starter (`healthcore-starter-mpi`)

## 1. Purpose
Provides Golden Record registration, AES-256-GCM encrypted identifiers with HMAC blind indexes, Fellegi-Sunter probabilistic duplicate matching, reversible merge/unmerge with cascading events, and FHIR Patient search.

## 2. Implemented Capabilities
- Spring Boot 4.0 auto-configured starter (`AutoConfiguration.imports` + `@EnableJpaRepositories(considerNestedRepositories = true)`).
- Tenant-scoped domain services, JPA entities, and secured REST/standards endpoints.
- Cross-database Liquibase schema support (PostgreSQL, Microsoft SQL Server, MySQL).

## 3. Explicitly Unsupported Capabilities
Does not provide biometric hardware capture SDKs or national civil registry federation connectors out of the box.

## 4. Domain Model
- `PatientAggregateEntity` (`hc_patients`)
- `PatientDemographicsEntity` (`hc_patient_demographics`)
- `PatientIdentifierEntity` (`hc_patient_identifiers`)
- `PatientMergeHistoryEntity` (`hc_patient_merges`)
- `PatientPhoneticIndexEntity` (`hc_patient_phonetics`)

## 5. Database Migrations
- `db/changelog/mpi/001-mpi.xml`
- `db/changelog/mpi/002-mpi-fhir-demographics.xml`

## 6. REST APIs
- `POST /api/v1/mpi/patients`
- `GET /api/v1/mpi/patients/match`
- `GET /api/v1/mpi/patients/fuzzy-search`
- `POST /api/v1/mpi/patients/merge`
- `POST /api/v1/mpi/patients/unmerge`
- `POST /fhir/r4/Patient`
- `GET /fhir/r4/Patient/{id}`
- `PUT /fhir/r4/Patient/{id}`

## 7. FHIR / HL7 / Standards Mappings
- Maps internal domain identifiers using UUIDv7 and standard clinical terminologies (LOINC, SNOMED CT, ICD-10/11, RxNorm, UCUM) where applicable.
- See `docs/API_CONFORMANCE_REPORT.md` for verified FHIR R4, HL7 v2.5.1, IHE, and DICOMweb conformance boundaries.

## 8. Authorization Model
- Zero `or isAuthenticated()` fallbacks on clinical or patient-data endpoints.
- Method-level `@PreAuthorize` guards detected in this module:
- `hasAuthority('PERM_patient:read')`
- `hasAuthority('PERM_patient:write') or hasAuthority('PERM_facility:manage')`
- `hasAuthority('PERM_patient:write')`

## 9. Tenant Scope
- All clinical and operational records are scoped by `facility_id` via `FacilityContext`, JWT `facility_id` validation, Hibernate `facilityFilter`, and PostgreSQL Row-Level Security (`PostgresRlsSessionAspect`).

## 10. Audit Events
`MPI_MERGE_PATIENT`, `MPI_REGISTER_PATIENT`, `MPI_UNMERGE_PATIENT`, `PATIENT_CREATE_FHIR`, `PATIENT_CREATE`, `PATIENT_DEACTIVATE`, `PATIENT_DELETE`, `PATIENT_READ_FHIR`, `PATIENT_READ`, `PATIENT_SEARCH_FHIR`, `PATIENT_SEARCH`, `PATIENT_UPDATE_FHIR`, `PATIENT_UPDATE`

## 11. Configuration
- Configured via standard Spring Boot properties and environment variables documented in `.env.example` and `docs/PRODUCTION_RUNBOOK.md`.
- No hardcoded production secrets; `ProductionSecretsStartupValidator` fails closed in production profiles.

## 12. Deployment Requirements
- Java 25 (JDK 25), Spring Boot 4.0, PostgreSQL 16+ (primary) or MSSQL / MySQL, and OIDC/Keycloak issuer.
- Include `com.opeshealth.healthcore:healthcore-starter-mpi:1.0.0` with `healthcore-bom`.

## 13. Failure Behavior
- Fails closed (`AccessDeniedException` / `SecurityException` / `IllegalStateException`) on missing tenant context, unauthorized access, invalid clinical state transitions, or missing mandatory safety context.
- Never returns fabricated `200 OK` responses for unsupported operations.

## 14. Clinical Validation Status
- **Status:** PRE-PRODUCTION / ENGINEERING BUILD.
- Requires institutional HIM review of Fellegi-Sunter match/review thresholds before clinical go-live.

## 15. Test Evidence
- Module test classes:
- `MpiCommandServiceTest.java`
- Verification command (JDK 25 CI): `mvn -B -ntp test -pl healthcore-starter-mpi -am`

## 16. Known Limitations
- Live Maven/JUnit/Testcontainers execution must be verified in JDK 25 + Docker CI.
- Refer to `docs/KNOWN_LIMITATIONS_REGISTER.md` for full platform-level caveats and go-live gates.
