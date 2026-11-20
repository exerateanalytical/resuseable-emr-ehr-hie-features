# Opes HealthCore Starter — Tariffs, Healthcare Coding & Payer Claims

## 1. Purpose
Durable revenue-cycle starter delivering facility/payer tariff schedules, human-reviewed ICD-10/ICD-11/CPT/DRG clinical coding workbench, insurance eligibility and prior authorization workflows, X12 837 / FHIR claim submission, X12 835 / ERA remittance auto-posting, denial appeals, and refund reconciliation.

## 2. Implemented Capabilities
- Primary components: `RevenueCodingClaimsController`, `RevenueCodingClaimsService`
- Production Java source files: 3
- Strict Pure Java 25 + Spring Boot 4.0 auto-configuration (`AutoConfiguration.imports`) with zero Node.js/TypeScript dependencies.

## 3. Explicitly Unsupported Capabilities
- Does not claim autonomous clinical diagnosis, autonomous prescribing, or regulatory certification out-of-the-box.
- Does not bypass tenant facility isolation, RBAC/ABAC method security, or Liquibase schema governance.

## 4. Domain Model
- JPA Entities: `ClinicalCodingRecordEntity`, `FacilityTariffEntity`, `InsuranceAuthorizationEntity`, `PayerClaimEntity`
- Relational Tables: `hc_rev_clinical_coding`, `hc_rev_facility_tariffs`, `hc_rev_insurance_auth`, `hc_rev_payer_claims`

## 5. Database Migrations
Sole migration authority: **Liquibase** (portable `${uuid_type}`, `${ts_type}`, `${clob_type}`, `${bool_type}` across PostgreSQL, SQL Server, and MySQL).
- `src/main/resources/db/changelog/revenue_claims/001-revenue-coding-claims.xml`

## 6. REST APIs
- `GET /api/v1/revenue-claims/claims/summary`
- `GET /api/v1/revenue-claims/claims`
- `GET /api/v1/revenue-claims/tariffs`
- `POST /api/v1/revenue-claims/authorizations/{authId}/decide`
- `POST /api/v1/revenue-claims/authorizations`
- `POST /api/v1/revenue-claims/claims/{claimId}/appeal`
- `POST /api/v1/revenue-claims/claims/{claimId}/era`
- `POST /api/v1/revenue-claims/claims/{claimId}/refund`
- `POST /api/v1/revenue-claims/claims`
- `POST /api/v1/revenue-claims/coding/{codingId}/approve`
- `POST /api/v1/revenue-claims/coding`
- `POST /api/v1/revenue-claims/tariffs`

## 7. FHIR or HL7 Mappings
- Standards footprint: FHIR R4, X12

## 8. Authorization Model
- Enforces Spring Security OIDC/OAuth2 resource-server authorities with zero `or isAuthenticated()` fallback bypasses:
- `hasAuthority('PERM_billing:read')`
- `hasAuthority('PERM_billing:write')`
- `hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_billing:write')`

## 9. Tenant Scope
- Strictly scoped by authenticated `FacilityContext` (`facility_id`) and Hibernate `facilityFilter` / PostgreSQL Row-Level Security (RLS) policies where relational persistence is used.

## 10. Audit Events
- `REV_CLAIM_APPEALED`, `REV_CLAIM_REFUND`, `REV_CLAIM_SUBMITTED`, `REV_CODING_APPROVED`, `REV_CODING_ASSIGNED`, `REV_ELIGIBILITY_CHECK`, `REV_ERA_POSTED_`, `REV_PRIOR_AUTH_`, `REV_TARIFF_UPSERT`

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
- `RevenueCodingClaimsServiceTest` (unit/contract JUnit 5 test suite)

## 16. Known Limitations
- Live Maven (`mvn -B clean verify`) and multi-database Testcontainers execution require a JDK 25 + Docker CI runner; see repository `docs/KNOWN_LIMITATIONS_REGISTER.md` for global boundaries.
