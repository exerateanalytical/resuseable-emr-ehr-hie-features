# Opes HealthCore Composable Clinical & HIE Workflow Engine Starter

## 1. Purpose
Opes HealthCore module `healthcore-starter-workflow-engine`.

## 2. Implemented Capabilities
- Primary components: `WorkflowEngineAutoConfiguration`, `WorkflowGatewayController`, `WorkflowOrchestrationService`
- Production Java source files: 5
- Strict Pure Java 25 + Spring Boot 4.0 auto-configuration (`AutoConfiguration.imports`) with zero Node.js/TypeScript dependencies.

## 3. Explicitly Unsupported Capabilities
- Does not claim autonomous clinical diagnosis, autonomous prescribing, or regulatory certification out-of-the-box.
- Does not bypass tenant facility isolation, RBAC/ABAC method security, or Liquibase schema governance.

## 4. Domain Model
- JPA Entities: `WorkflowDefinitionEntity`, `WorkflowEngineAutoConfiguration`, `WorkflowInstanceEntity`, `WorkflowStepLogEntity`
- Relational Tables: `hc_workflow_definitions`, `hc_workflow_instances`, `hc_workflow_step_logs`

## 5. Database Migrations
Sole migration authority: **Liquibase** (portable `${uuid_type}`, `${ts_type}`, `${clob_type}`, `${bool_type}` across PostgreSQL, SQL Server, and MySQL).
- `src/main/resources/db/changelog/workflow/001-workflow-engine.xml`

## 6. REST APIs
- `GET /api/v1/workflows/catalog`
- `GET /api/v1/workflows/definitions`
- `GET /api/v1/workflows/instances/active`
- `GET /api/v1/workflows/instances/{instanceId}/timeline`
- `POST /api/v1/workflows/catalog/seed`
- `POST /api/v1/workflows/definitions`
- `POST /api/v1/workflows/instances/{instanceId}/transition`
- `POST /api/v1/workflows/instances`

## 7. FHIR or HL7 Mappings
- Standards footprint: ASTM, FHIR R4, HL7 v2, IHE

## 8. Authorization Model
- Enforces Spring Security OIDC/OAuth2 resource-server authorities with zero `or isAuthenticated()` fallback bypasses:
- `hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_lab:verify') or hasAuthority('PERM_rx:dispense') or hasAuthority('PERM_chw:visit:write') or hasAuthority('PERM_facility:manage')`
- `hasAuthority('PERM_facility:manage') or hasAuthority('PERM_system:admin')`
- `hasAuthority('PERM_facility:manage') or hasAuthority('PERM_template:publish') or hasAuthority('PERM_system:admin')`
- `hasAuthority('PERM_patient:read') or hasAuthority('PERM_audit:read')`
- `hasAuthority('PERM_patient:read') or hasAuthority('PERM_chw:visit:write') or hasAuthority('PERM_facility:manage')`
- `hasAuthority('PERM_patient:read') or hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_chw:visit:write') or hasAuthority('PERM_facility:manage')`

## 9. Tenant Scope
- Strictly scoped by authenticated `FacilityContext` (`facility_id`) and Hibernate `facilityFilter` / PostgreSQL Row-Level Security (RLS) policies where relational persistence is used.

## 10. Audit Events
- `WORKFLOW_DEFINE_CUSTOM`, `WORKFLOW_SEED_CATALOG`, `WORKFLOW_START`, `WORKFLOW_TRANSITION`

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
- `WorkflowOrchestrationServiceTest` (unit/contract JUnit 5 test suite)

## 16. Known Limitations
- Live Maven (`mvn -B clean verify`) and multi-database Testcontainers execution require a JDK 25 + Docker CI runner; see repository `docs/KNOWN_LIMITATIONS_REGISTER.md` for global boundaries.
