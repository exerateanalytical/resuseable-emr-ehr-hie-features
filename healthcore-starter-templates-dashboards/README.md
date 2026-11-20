# Opes HealthCore 600+ Templates, Dashboards & Onboarding Starter

## 1. Purpose
Opes HealthCore module `healthcore-starter-templates-dashboards`.

## 2. Implemented Capabilities
- Primary components: `TemplatesAndOperationsService`, `TemplatesAndOpsController`
- Production Java source files: 4
- Strict Pure Java 25 + Spring Boot 4.0 auto-configuration (`AutoConfiguration.imports`) with zero Node.js/TypeScript dependencies.

## 3. Explicitly Unsupported Capabilities
- Does not claim autonomous clinical diagnosis, autonomous prescribing, or regulatory certification out-of-the-box.
- Does not bypass tenant facility isolation, RBAC/ABAC method security, or Liquibase schema governance.

## 4. Domain Model
- JPA Entities: `BackupManifestEntity`, `DocumentTemplateEntity`, `EdgeDrStateEntity`, `FacilityNodeEntity`, `QuestionnaireResponseEntity`, `TemplatesAndDashboardsAutoConfiguration`
- Relational Tables: `hc_backup_manifests`, `hc_document_templates`, `hc_edge_dr_state`, `hc_facility_nodes`, `hc_questionnaire_responses`

## 5. Database Migrations
Sole migration authority: **Liquibase** (portable `${uuid_type}`, `${ts_type}`, `${clob_type}`, `${bool_type}` across PostgreSQL, SQL Server, and MySQL).
- `src/main/resources/db/changelog/templates_dashboards/001-templates-dashboards.xml`
- `src/main/resources/db/changelog/templates_dashboards/002-templates-responses.xml`

## 6. REST APIs
- `GET /api/v1/platform/dashboards/{roleKey}`
- `GET /api/v1/platform/export/patients.csv`
- `GET /api/v1/platform/templates/responses`
- `GET /api/v1/platform/templates`
- `POST /api/v1/platform/dr/backups`
- `POST /api/v1/platform/dr/failover-mode`
- `POST /api/v1/platform/dr/verify-restore/{manifestId}`
- `POST /api/v1/platform/import/csv`
- `POST /api/v1/platform/import/xlsx`
- `POST /api/v1/platform/onboarding/facilities`
- `POST /api/v1/platform/templates/{templateCode}/pdf`
- `POST /api/v1/platform/templates/{templateCode}/responses`

## 7. FHIR or HL7 Mappings
- Standards footprint: ASTM, DICOM, FHIR R4

## 8. Authorization Model
- Enforces Spring Security OIDC/OAuth2 resource-server authorities with zero `or isAuthenticated()` fallback bypasses:
- `hasAuthority('PERM_audit:export') or hasAuthority('PERM_facility:manage')`
- `hasAuthority('PERM_dr:backup') or hasAuthority('PERM_system:admin')`
- `hasAuthority('PERM_facility:manage') or hasAuthority('PERM_system:admin')`
- `hasAuthority('PERM_facility:onboard') or hasAuthority('PERM_system:admin')`

## 9. Tenant Scope
- Strictly scoped by authenticated `FacilityContext` (`facility_id`) and Hibernate `facilityFilter` / PostgreSQL Row-Level Security (RLS) policies where relational persistence is used.

## 10. Audit Events
- `DR_BACKUP_SNAPSHOT`, `FACILITY_ONBOARD`

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
- `TemplatesAndOperationsServiceTest` (unit/contract JUnit 5 test suite)

## 16. Known Limitations
- Live Maven (`mvn -B clean verify`) and multi-database Testcontainers execution require a JDK 25 + Docker CI runner; see repository `docs/KNOWN_LIMITATIONS_REGISTER.md` for global boundaries.
