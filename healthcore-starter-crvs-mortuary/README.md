# Opes HealthCore Starter — CRVS & Mortuary Custody

## 1. Purpose
Composable CRVS Birth, WHO MCCD Cause of Death & Mortuary Chain-of-Custody starter.

## 2. Implemented Capabilities
- Primary components: `CrvsAndMortuaryChainOfCustodyService`, `CrvsMortuaryComplianceController`
- Production Java source files: 1
- Strict Pure Java 25 + Spring Boot 4.0 auto-configuration (`AutoConfiguration.imports`) with zero Node.js/TypeScript dependencies.

## 3. Explicitly Unsupported Capabilities
- Does not claim autonomous clinical diagnosis, autonomous prescribing, or regulatory certification out-of-the-box.
- Does not bypass tenant facility isolation, RBAC/ABAC method security, or Liquibase schema governance.

## 4. Domain Model
- JPA Entities: Delegates to core domain entities or provides platform infrastructure.
- Relational Tables: No module-specific database tables (reuses core starter schemas).

## 5. Database Migrations
Sole migration authority: **Liquibase** (portable `${uuid_type}`, `${ts_type}`, `${clob_type}`, `${bool_type}` across PostgreSQL, SQL Server, and MySQL).
- Managed via upstream core starter Liquibase changelogs.

## 6. REST APIs
- `POST /api/v1/crvs-mortuary/chain-of-custody/release`
- `POST /api/v1/crvs-mortuary/mccd-validate-and-register`

## 7. FHIR or HL7 Mappings
- Standards footprint: Aligned with HealthCore canonical internal domain contracts and FHIR R4 interoperability conventions.

## 8. Authorization Model
- Enforces Spring Security OIDC/OAuth2 resource-server authorities with zero `or isAuthenticated()` fallback bypasses:
- `hasAuthority('PERM_crvs:certify') or hasAuthority('PERM_facility:manage')`
- `hasAuthority('PERM_crvs:certify')`

## 9. Tenant Scope
- Strictly scoped by authenticated `FacilityContext` (`facility_id`) and Hibernate `facilityFilter` / PostgreSQL Row-Level Security (RLS) policies where relational persistence is used.

## 10. Audit Events
- `CRVS_WHO_MCCD_REGISTER`, `MORTUARY_CUSTODY_RELEASE`

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
- `CrvsMortuaryStarterTest` (unit/contract JUnit 5 test suite)

## 16. Known Limitations
- Live Maven (`mvn -B clean verify`) and multi-database Testcontainers execution require a JDK 25 + Docker CI runner; see repository `docs/KNOWN_LIMITATIONS_REGISTER.md` for global boundaries.
