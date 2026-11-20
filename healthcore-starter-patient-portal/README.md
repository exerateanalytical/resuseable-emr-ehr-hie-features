# Patient Portal & Proxy Access Starter (`healthcore-starter-patient-portal`)

## 1. Purpose
Enables patient self-enrollment, scoped/expiring proxy caregiver grants, patient-clinician secure messaging threads, and tenant-scoped patient-authorized record access.

## 2. Implemented Capabilities
- Spring Boot 4.0 auto-configured starter (`AutoConfiguration.imports` + `@EnableJpaRepositories(considerNestedRepositories = true)`).
- Tenant-scoped domain services, JPA entities, and secured REST/standards endpoints.
- Cross-database Liquibase schema support (PostgreSQL, Microsoft SQL Server, MySQL).

## 3. Explicitly Unsupported Capabilities
Does not include external identity proofing (eKYC) or push-notification mobile broker keys.

## 4. Domain Model
- `PatientPortalAccountEntity` (`hc_portal_accounts`)
- `PatientProxyGrantEntity` (`hc_portal_proxy_grants`)
- `PortalMessageEntity` (`hc_portal_messages`)
- `PortalMessageThreadEntity` (`hc_portal_message_threads`)

## 5. Database Migrations
- `db/changelog/patient_portal/001-patient-portal.xml`

## 6. REST APIs
- `POST /api/v1/portal/accounts`
- `POST /api/v1/portal/proxies`
- `DELETE /api/v1/portal/proxies/{grantId}`
- `GET /api/v1/portal/patients/{patientId}/appointments`
- `GET /api/v1/portal/patients/{patientId}/results`
- `POST /api/v1/portal/threads`
- `POST /api/v1/portal/threads/{threadId}/messages`
- `GET /api/v1/portal/patients/{patientId}/threads`
- `GET /api/v1/portal/threads/{threadId}/messages`

## 7. FHIR / HL7 / Standards Mappings
- Maps internal domain identifiers using UUIDv7 and standard clinical terminologies (LOINC, SNOMED CT, ICD-10/11, RxNorm, UCUM) where applicable.
- See `docs/API_CONFORMANCE_REPORT.md` for verified FHIR R4, HL7 v2.5.1, IHE, and DICOMweb conformance boundaries.

## 8. Authorization Model
- Zero `or isAuthenticated()` fallbacks on clinical or patient-data endpoints.
- Method-level `@PreAuthorize` guards detected in this module:
- `hasAuthority('PERM_patient:write') or hasAuthority('PERM_facility:manage')`
- `hasAuthority('PERM_self:consent:manage') or hasAuthority('PERM_facility:manage')`
- `hasAuthority('PERM_self:record:read') or hasAuthority('PERM_patient:read')`
- `hasAuthority('PERM_self:record:read') or hasAuthority('PERM_self:appointment:request')`
- `hasAuthority('PERM_self:record:read')`

## 9. Tenant Scope
- All clinical and operational records are scoped by `facility_id` via `FacilityContext`, JWT `facility_id` validation, Hibernate `facilityFilter`, and PostgreSQL Row-Level Security (`PostgresRlsSessionAspect`).

## 10. Audit Events
`PORTAL_ENROLL_ACCOUNT`, `PORTAL_GRANT_PROXY`, `PORTAL_OPEN_THREAD`, `PORTAL_REVOKE_PROXY`, `PORTAL_SEND_MESSAGE`

## 11. Configuration
- Configured via standard Spring Boot properties and environment variables documented in `.env.example` and `docs/PRODUCTION_RUNBOOK.md`.
- No hardcoded production secrets; `ProductionSecretsStartupValidator` fails closed in production profiles.

## 12. Deployment Requirements
- Java 25 (JDK 25), Spring Boot 4.0, PostgreSQL 16+ (primary) or MSSQL / MySQL, and OIDC/Keycloak issuer.
- Include `com.opeshealth.healthcore:healthcore-starter-patient-portal:1.0.0` with `healthcore-bom`.

## 13. Failure Behavior
- Fails closed (`AccessDeniedException` / `SecurityException` / `IllegalStateException`) on missing tenant context, unauthorized access, invalid clinical state transitions, or missing mandatory safety context.
- Never returns fabricated `200 OK` responses for unsupported operations.

## 14. Clinical Validation Status
- **Status:** PRE-PRODUCTION / ENGINEERING BUILD.
- Patient release delay policies for sensitive/critical results require institutional medical-records approval.

## 15. Test Evidence
- Module test classes:
- Covered via `healthcore-quickstart-host` integration/safety suites.
- Verification command (JDK 25 CI): `mvn -B -ntp test -pl healthcore-starter-patient-portal -am`

## 16. Known Limitations
- Live Maven/JUnit/Testcontainers execution must be verified in JDK 25 + Docker CI.
- Refer to `docs/KNOWN_LIMITATIONS_REGISTER.md` for full platform-level caveats and go-live gates.
