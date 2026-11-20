# Pharmacy (PIS) & Lab Diagnostics (LIS) Starter (`healthcore-starter-pharmacy-lis`)

## 1. Purpose
Delivers concurrency-safe FEFO medication lot dispensing with atomic stock decrement, expiry quarantine, Schedule II/III dual-witness signoff, LIS specimen accessioning, Westgard QC release gating, and critical panic escalation.

## 2. Implemented Capabilities
- Spring Boot 4.0 auto-configured starter (`AutoConfiguration.imports` + `@EnableJpaRepositories(considerNestedRepositories = true)`).
- Tenant-scoped domain services, JPA entities, and secured REST/standards endpoints.
- Cross-database Liquibase schema support (PostgreSQL, Microsoft SQL Server, MySQL).

## 3. Explicitly Unsupported Capabilities
Does not include automated robotic pharmacy dispensing hardware interfaces or national controlled-substance DEA/EPCS network connectors.

## 4. Domain Model
- `DiagnosticReportEntity` (`hc_diagnostic_reports`)
- `LabSpecimenEntity` (`hc_lab_specimens`)
- `MedicationBatchEntity` (`hc_medication_batches`)
- `PharmacyDispenseEntity` (`hc_pharmacy_dispenses`)

## 5. Database Migrations
- `db/changelog/pharmacy_lis/001-pharmacy-lis.xml`

## 6. REST APIs
- `POST /api/v1/pharmacy-lis/pharmacy/batches`
- `POST /api/v1/pharmacy-lis/pharmacy/dispense`
- `POST /api/v1/pharmacy-lis/lis/specimens`
- `POST /api/v1/pharmacy-lis/lis/reports/verify`

## 7. FHIR / HL7 / Standards Mappings
- Maps internal domain identifiers using UUIDv7 and standard clinical terminologies (LOINC, SNOMED CT, ICD-10/11, RxNorm, UCUM) where applicable.
- See `docs/API_CONFORMANCE_REPORT.md` for verified FHIR R4, HL7 v2.5.1, IHE, and DICOMweb conformance boundaries.

## 8. Authorization Model
- Zero `or isAuthenticated()` fallbacks on clinical or patient-data endpoints.
- Method-level `@PreAuthorize` guards detected in this module:
- `hasAuthority('PERM_lab:accession')`
- `hasAuthority('PERM_lab:verify')`
- `hasAuthority('PERM_pharmacy:stock:adjust')`
- `hasAuthority('PERM_rx:dispense') or hasAuthority('PERM_rx:controlled:dispense')`

## 9. Tenant Scope
- All clinical and operational records are scoped by `facility_id` via `FacilityContext`, JWT `facility_id` validation, Hibernate `facilityFilter`, and PostgreSQL Row-Level Security (`PostgresRlsSessionAspect`).

## 10. Audit Events
`LIS_VERIFY_RESULT`, `PIS_DISPENSE_FEFO`

## 11. Configuration
- Configured via standard Spring Boot properties and environment variables documented in `.env.example` and `docs/PRODUCTION_RUNBOOK.md`.
- No hardcoded production secrets; `ProductionSecretsStartupValidator` fails closed in production profiles.

## 12. Deployment Requirements
- Java 25 (JDK 25), Spring Boot 4.0, PostgreSQL 16+ (primary) or MSSQL / MySQL, and OIDC/Keycloak issuer.
- Include `com.opeshealth.healthcore:healthcore-starter-pharmacy-lis:1.0.0` with `healthcore-bom`.

## 13. Failure Behavior
- Fails closed (`AccessDeniedException` / `SecurityException` / `IllegalStateException`) on missing tenant context, unauthorized access, invalid clinical state transitions, or missing mandatory safety context.
- Never returns fabricated `200 OK` responses for unsupported operations.

## 14. Clinical Validation Status
- **Status:** PRE-PRODUCTION / ENGINEERING BUILD.
- FEFO/controlled-substance SOPs and Westgard QC control limits require pharmacy & pathology clinical sign-off.

## 15. Test Evidence
- Module test classes:
- `PharmacyLisSafetyTest.java`
- Verification command (JDK 25 CI): `mvn -B -ntp test -pl healthcore-starter-pharmacy-lis -am`

## 16. Known Limitations
- Live Maven/JUnit/Testcontainers execution must be verified in JDK 25 + Docker CI.
- Refer to `docs/KNOWN_LIMITATIONS_REGISTER.md` for full platform-level caveats and go-live gates.
