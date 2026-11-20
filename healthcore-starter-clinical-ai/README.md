# Governed Clinical AI Starter (`healthcore-starter-clinical-ai`)

## 1. Purpose
Provides durable tenant AI policy & kill-switch, persisted clinician draft & attestation ledger, PHI risk-reduction redaction gateway, and fail-closed audit logging for ambient scribe, summarization, and coding assistance.

## 2. Implemented Capabilities
- Spring Boot 4.0 auto-configured starter (`AutoConfiguration.imports` + `@EnableJpaRepositories(considerNestedRepositories = true)`).
- Tenant-scoped domain services, JPA entities, and secured REST/standards endpoints.
- Cross-database Liquibase schema support (PostgreSQL, Microsoft SQL Server, MySQL).

## 3. Explicitly Unsupported Capabilities
Strictly forbids autonomous clinical diagnosis, autonomous prescribing, direct unapproved release to patients, or claims of guaranteed HIPAA Safe Harbor de-identification via regex alone.

## 4. Domain Model
- `ClinicalAiAuditEntryEntity` (`hc_clinical_ai_audit_entries`)
- `ClinicalAiDraftEntity` (`hc_clinical_ai_drafts`)
- `TenantAiPolicyEntity` (`hc_clinical_ai_tenant_policies`)

## 5. Database Migrations
- `db/changelog/clinical_ai/001-clinical-ai-governance.xml`

## 6. REST APIs
- Exposed via host composition or internal service SPI.

## 7. FHIR / HL7 / Standards Mappings
- Maps internal domain identifiers using UUIDv7 and standard clinical terminologies (LOINC, SNOMED CT, ICD-10/11, RxNorm, UCUM) where applicable.
- See `docs/API_CONFORMANCE_REPORT.md` for verified FHIR R4, HL7 v2.5.1, IHE, and DICOMweb conformance boundaries.

## 8. Authorization Model
- Zero `or isAuthenticated()` fallbacks on clinical or patient-data endpoints.
- Method-level `@PreAuthorize` guards detected in this module:
- Enforced via Spring Security method/filter policies and `FacilityContext`.

## 9. Tenant Scope
- All clinical and operational records are scoped by `facility_id` via `FacilityContext`, JWT `facility_id` validation, Hibernate `facilityFilter`, and PostgreSQL Row-Level Security (`PostgresRlsSessionAspect`).

## 10. Audit Events
Tamper-evident clinical audit trail via `ClinicalAuditService` / `@ClinicalAudit`.

## 11. Configuration
- Configured via standard Spring Boot properties and environment variables documented in `.env.example` and `docs/PRODUCTION_RUNBOOK.md`.
- No hardcoded production secrets; `ProductionSecretsStartupValidator` fails closed in production profiles.

## 12. Deployment Requirements
- Java 25 (JDK 25), Spring Boot 4.0, PostgreSQL 16+ (primary) or MSSQL / MySQL, and OIDC/Keycloak issuer.
- Include `com.opeshealth.healthcore:healthcore-starter-clinical-ai:1.0.0` with `healthcore-bom`.

## 13. Failure Behavior
- Fails closed (`AccessDeniedException` / `SecurityException` / `IllegalStateException`) on missing tenant context, unauthorized access, invalid clinical state transitions, or missing mandatory safety context.
- Never returns fabricated `200 OK` responses for unsupported operations.

## 14. Clinical Validation Status
- **Status:** PRE-PRODUCTION / ENGINEERING BUILD.
- Requires institutional AI governance committee, privacy officer, and clinical safety review prior to enabling external LLM providers.

## 15. Test Evidence
- Module test classes:
- `ClinicalAiGovernanceAndRedactionTest.java`
- Verification command (JDK 25 CI): `mvn -B -ntp test -pl healthcore-starter-clinical-ai -am`

## 16. Known Limitations
- Live Maven/JUnit/Testcontainers execution must be verified in JDK 25 + Docker CI.
- Refer to `docs/KNOWN_LIMITATIONS_REGISTER.md` for full platform-level caveats and go-live gates.
