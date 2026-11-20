# Blood Bank & Hemovigilance Starter (`healthcore-starter-blood-bank`)

## 1. Purpose
Manages blood unit inventory, ABO/Rh compatibility cross-matching, transfusion issuance checks, and hemovigilance adverse reaction tracking.

## 2. Implemented Capabilities
- Spring Boot 4.0 auto-configured starter (`AutoConfiguration.imports` + `@EnableJpaRepositories(considerNestedRepositories = true)`).
- Tenant-scoped domain services, JPA entities, and secured REST/standards endpoints.
- Cross-database Liquibase schema support (PostgreSQL, Microsoft SQL Server, MySQL).

## 3. Explicitly Unsupported Capabilities
Does not replace ISBT-128 physical blood-bag barcode hardware verification or national blood transfusion center APIs.

## 4. Domain Model
- Delegates persistence to core/dependent starter domain entities.

## 5. Database Migrations
- Uses shared core/dependent starter migrations (no standalone table changelog).

## 6. REST APIs
- `GET /api/v1/blood-bank/compatibility-check`
- `POST /api/v1/blood-bank/units/register`
- `POST /api/v1/blood-bank/crossmatch-and-issue`
- `GET /api/v1/blood-bank/inventory-summary`

## 7. FHIR / HL7 / Standards Mappings
- Maps internal domain identifiers using UUIDv7 and standard clinical terminologies (LOINC, SNOMED CT, ICD-10/11, RxNorm, UCUM) where applicable.
- See `docs/API_CONFORMANCE_REPORT.md` for verified FHIR R4, HL7 v2.5.1, IHE, and DICOMweb conformance boundaries.

## 8. Authorization Model
- Zero `or isAuthenticated()` fallbacks on clinical or patient-data endpoints.
- Method-level `@PreAuthorize` guards detected in this module:
- `hasAuthority('PERM_bloodbank:manage') or hasAuthority('PERM_facility:manage')`
- `hasAuthority('PERM_bloodbank:manage') or hasAuthority('PERM_patient:read')`
- `hasAuthority('PERM_bloodbank:manage')`

## 9. Tenant Scope
- All clinical and operational records are scoped by `facility_id` via `FacilityContext`, JWT `facility_id` validation, Hibernate `facilityFilter`, and PostgreSQL Row-Level Security (`PostgresRlsSessionAspect`).

## 10. Audit Events
`BLOOD_BANK_COMPATIBLE_CROSSMATCH`, `BLOOD_BANK_REGISTER_UNIT`

## 11. Configuration
- Configured via standard Spring Boot properties and environment variables documented in `.env.example` and `docs/PRODUCTION_RUNBOOK.md`.
- No hardcoded production secrets; `ProductionSecretsStartupValidator` fails closed in production profiles.

## 12. Deployment Requirements
- Java 25 (JDK 25), Spring Boot 4.0, PostgreSQL 16+ (primary) or MSSQL / MySQL, and OIDC/Keycloak issuer.
- Include `com.opeshealth.healthcore:healthcore-starter-blood-bank:1.0.0` with `healthcore-bom`.

## 13. Failure Behavior
- Fails closed (`AccessDeniedException` / `SecurityException` / `IllegalStateException`) on missing tenant context, unauthorized access, invalid clinical state transitions, or missing mandatory safety context.
- Never returns fabricated `200 OK` responses for unsupported operations.

## 14. Clinical Validation Status
- **Status:** PRE-PRODUCTION / ENGINEERING BUILD.
- ABO/Rh compatibility matrix and transfusion protocols require blood-bank medical director validation.

## 15. Test Evidence
- Module test classes:
- `BloodBankCompatibilityTest.java`
- Verification command (JDK 25 CI): `mvn -B -ntp test -pl healthcore-starter-blood-bank -am`

## 16. Known Limitations
- Live Maven/JUnit/Testcontainers execution must be verified in JDK 25 + Docker CI.
- Refer to `docs/KNOWN_LIMITATIONS_REGISTER.md` for full platform-level caveats and go-live gates.
