# Opes HealthCore Quickstart Reference Host

## 1. Purpose
Opes HealthCore module `healthcore-quickstart-host`.

## 2. Implemented Capabilities
- Primary components: `AmbientClinicalScribeController`, `AmbientClinicalScribeService`, `AntimicrobialStewardshipService`, `AuditEventController`, `BcmaMedicationSafetyService`, `BundleController`, `BundleService`, `CalibratedClinicalDecisionController`, `CalibratedClinicalDecisionService`, `CapacityOperationsAiController`, `CarePlanPathwayController`, `CarePlanPathwayService`
- Production Java source files: 103
- Strict Pure Java 25 + Spring Boot 4.0 auto-configuration (`AutoConfiguration.imports`) with zero Node.js/TypeScript dependencies.

## 3. Explicitly Unsupported Capabilities
- Does not claim autonomous clinical diagnosis, autonomous prescribing, or regulatory certification out-of-the-box.
- Does not bypass tenant facility isolation, RBAC/ABAC method security, or Liquibase schema governance.

## 4. Domain Model
- JPA Entities: `ObservationEntity`, `PractitionerEntity`, `PractitionerPersistenceConfiguration`
- Relational Tables: `hc_observations`, `hc_practitioners`

## 5. Database Migrations
Sole migration authority: **Liquibase** (portable `${uuid_type}`, `${ts_type}`, `${clob_type}`, `${bool_type}` across PostgreSQL, SQL Server, and MySQL).
- `src/main/resources/db/changelog/core/003-postgresql-optimizations.xml`
- `src/main/resources/db/changelog/db.changelog-master.xml`
- `src/main/resources/db/changelog/quickstart/001-practitioners.xml`
- `src/main/resources/db/changelog/quickstart/002-observations.xml`

## 6. REST APIs
- `DELETE /api/v1/encounters/{id}`
- `DELETE /api/v1/fhir/Consent/{consentId}`
- `DELETE /api/v1/observations/{id}`
- `DELETE /api/v1/patients/{id}`
- `DELETE /api/v1/practitioners/{id}`
- `GET /api/v1/admin/tenants/{facilityId}/feature-flags/{flagKey}`
- `GET /api/v1/admin/tenants/{facilityId}`
- `GET /api/v1/admin/tenants`
- `GET /api/v1/ai/capacity/audit-trail/{facilityId}`
- `GET /api/v1/ai/governance/audit/entries`
- `GET /api/v1/ai/governance/audit/hipaa-report/{facilityId}`
- `GET /api/v1/ai/governance/audit/verify/{facilityId}`
- `GET /api/v1/ai/governance/drafts/{draftId}`
- `GET /api/v1/ai/governance/drafts`
- `GET /api/v1/ai/governance/tenants/{facilityId}/metrics`
- `GET /api/v1/ai/scribe/sessions/{sessionId}`
- `GET /api/v1/ams/antibiogram`
- `GET /api/v1/audit-events/compliance-report/patient/{patientId}`
- `GET /api/v1/audit-events/verify-chain`
- `GET /api/v1/bcma/administrations/{patientId}`
- `GET /api/v1/bcma/orders/{patientId}`
- `GET /api/v1/bulk-export/jobs/{jobId}/bundle`
- `GET /api/v1/care-plans/patient/{patientId}`
- `GET /api/v1/cds-services`
- `GET /api/v1/certificates/.well-known/jwks.json`

## 7. FHIR or HL7 Mappings
- Standards footprint: ASTM, CDA R2, DICOM, FHIR R4, HL7 v2, IHE, X12

## 8. Authorization Model
- Enforces Spring Security OIDC/OAuth2 resource-server authorities with zero `or isAuthenticated()` fallback bypasses:
- `hasAuthority('PERM_audit:read') or hasAuthority('PERM_facility:manage') or hasAuthority('PERM_patient:read')`
- `hasAuthority('PERM_audit:read') or hasAuthority('PERM_facility:manage')`
- `hasAuthority('PERM_patient:read') or hasAuthority('PERM_encounter:draft') or hasAuthority('PERM_encounter:sign')`
- `hasAuthority('PERM_patient:read') or hasAuthority('PERM_encounter:draft') or hasAuthority('PERM_order:draft') or hasAuthority('PERM_order:sign')`
- `hasAuthority('PERM_patient:read') or hasAuthority('PERM_encounter:draft') or hasAuthority('PERM_order:sign')`
- `hasAuthority('PERM_patient:read') or hasAuthority('PERM_facility:manage')`
- `hasAuthority('PERM_patient:read') or hasAuthority('PERM_lab:result:enter') or hasAuthority('PERM_lab:verify')`
- `hasAuthority('PERM_patient:read') or hasAuthority('PERM_staff:onboard') or hasAuthority('PERM_facility:manage')`
- `hasAuthority('PERM_patient:read') or hasAuthority('PERM_vitals:write') or hasAuthority('PERM_lab:result:enter')`
- `hasAuthority('PERM_patient:read')`
- `hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:draft') or hasAuthority('PERM_encounter:sign')`
- `hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_facility:manage')`

## 9. Tenant Scope
- Strictly scoped by authenticated `FacilityContext` (`facility_id`) and Hibernate `facilityFilter` / PostgreSQL Row-Level Security (RLS) policies where relational persistence is used.

## 10. Audit Events
- `AMS_AWARE_ORDER_REVIEW`, `AMS_ISOLATE_SUSCEPTIBILITY_RECORD`, `BCMA_BEDSIDE_SCAN_VERIFY`, `BCMA_EMAR_ORDER_SCHEDULE`, `BUNDLE_PROCESS`, `CLEARINGHOUSE_CLAIM_APPEAL`, `CLEARINGHOUSE_CLAIM_SUBMIT`, `CLEARINGHOUSE_EDI_835_RECONCILE`, `CLINICAL_AI_AMBIENT_SCRIBE_DRAFT`, `CLINICAL_AI_CALIBRATED_ESI_TRIAGE`, `CLINICAL_AI_CALIBRATED_PRIOR_AUTH_RUBRIC`, `CLINICAL_AI_CDS_ADVISORY`

## 11. Configuration
- Auto-configured via Spring Boot `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- Secrets and external endpoint credentials must be injected via environment variables or secret manager (never hardcoded).

## 12. Deployment Requirements
- Java 25 LTS runtime, Spring Boot 4.0+, and Liquibase-managed PostgreSQL 16+ (primary), Microsoft SQL Server, or MySQL 8.4+.

## 13. Failure Behavior
- Fails closed on missing `FacilityContext`, invalid clinical/regulatory inputs, schema violations, or missing authorization authorities (`400`, `403`, `404`, `409`, `503` ProblemDetails responses).

## 14. Clinical Validation Status
- Engineering implementation complete; independent hospital clinical safety review, local formulary/protocol validation, and jurisdictional certification are required prior to live patient care.

## 15. Test Evidence
- `AuditRemediationRegressionTest` (unit/contract JUnit 5 test suite)
- `ClinicalAiSuiteSafetyTest` (unit/contract JUnit 5 test suite)
- `ClinicalConcurrencySafetyTest` (unit/contract JUnit 5 test suite)
- `HealthCoreArchitectureTest` (unit/contract JUnit 5 test suite)
- `HealthCoreDomainSafetyTest` (unit/contract JUnit 5 test suite)
- `MultiDbTestcontainersIT` (unit/contract JUnit 5 test suite)
- `ReferenceHospitalUiAndSeederTest` (unit/contract JUnit 5 test suite)
- `ReferenceHospitalWorkflowsTest` (unit/contract JUnit 5 test suite)

## 16. Known Limitations
- Live Maven (`mvn -B clean verify`) and multi-database Testcontainers execution require a JDK 25 + Docker CI runner; see repository `docs/KNOWN_LIMITATIONS_REGISTER.md` for global boundaries.
