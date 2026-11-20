# Opes HealthCore Terminology & CDSS Starter

## 1. Purpose
Opes HealthCore module `healthcore-starter-terminology-cdss`.

## 2. Implemented Capabilities
- Primary components: `CdsHooksController`, `CdsHooksEngine`, `FhirTerminologySyncClient`, `TerminologyManagementController`, `TerminologyService`
- Production Java source files: 5
- Strict Pure Java 25 + Spring Boot 4.0 auto-configuration (`AutoConfiguration.imports`) with zero Node.js/TypeScript dependencies.

## 3. Explicitly Unsupported Capabilities
- Does not claim autonomous clinical diagnosis, autonomous prescribing, or regulatory certification out-of-the-box.
- Does not bypass tenant facility isolation, RBAC/ABAC method security, or Liquibase schema governance.

## 4. Domain Model
- JPA Entities: `CodeConceptEntity`, `ConceptClosureEntity`, `ConceptMapEntity`, `DdiRuleEntity`, `TerminologyCdssAutoConfiguration`
- Relational Tables: `hc_code_systems`, `hc_concept_closure`, `hc_concept_maps`, `hc_ddi_rules`

## 5. Database Migrations
Sole migration authority: **Liquibase** (portable `${uuid_type}`, `${ts_type}`, `${clob_type}`, `${bool_type}` across PostgreSQL, SQL Server, and MySQL).
- `src/main/resources/db/changelog/terminology/001-terminology-cdss.xml`

## 6. REST APIs
- `GET /api/v1/terminology/search`
- `GET /cds-services`
- `POST /api/v1/terminology/import`
- `POST /api/v1/terminology/sync-valueset`
- `POST /cds-services/opes-encounter-start`
- `POST /cds-services/opes-order-safety`
- `POST /cds-services/opes-order-select`
- `POST /cds-services/opes-vitals-panic`

## 7. FHIR or HL7 Mappings
- Standards footprint: FHIR R4, HL7 v2

## 8. Authorization Model
- Enforces Spring Security OIDC/OAuth2 resource-server authorities with zero `or isAuthenticated()` fallback bypasses:
- `hasAuthority('PERM_system:admin') or hasAuthority('PERM_facility:manage')`
- `hasAuthority('PERM_system:admin') or hasAuthority('PERM_template:publish')`

## 9. Tenant Scope
- Strictly scoped by authenticated `FacilityContext` (`facility_id`) and Hibernate `facilityFilter` / PostgreSQL Row-Level Security (RLS) policies where relational persistence is used.

## 10. Audit Events
- `TERMINOLOGY_CONCEPT_DEACTIVATE`, `TERMINOLOGY_CONCEPT_UPSERT`, `TERMINOLOGY_FHIR_SYNC`, `TERMINOLOGY_SUBSUMES`, `TERMINOLOGY_VALIDATE_BATCH`, `TERMINOLOGY_VALIDATE_CODE`

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
- `TerminologyAndCdssTest` (unit/contract JUnit 5 test suite)

## 16. Known Limitations
- Live Maven (`mvn -B clean verify`) and multi-database Testcontainers execution require a JDK 25 + Docker CI runner; see repository `docs/KNOWN_LIMITATIONS_REGISTER.md` for global boundaries.
