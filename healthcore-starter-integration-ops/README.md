# Opes HealthCore Starter — Integration Operations, Outbox & DLQ Replay

## 1. Purpose
Durable Integration Operations starter providing partner conformance profiles, idempotent outbox/inbox message ledgers, exponential backoff retry scheduling, dead-letter queue (DLQ) triage, manual/batch replay with immutable audit, and per-partner circuit-breaker governance.

## 2. Implemented Capabilities
- Primary components: `IntegrationOpsController`, `IntegrationOpsService`
- Production Java source files: 3
- Strict Pure Java 25 + Spring Boot 4.0 auto-configuration (`AutoConfiguration.imports`) with zero Node.js/TypeScript dependencies.

## 3. Explicitly Unsupported Capabilities
- Does not claim autonomous clinical diagnosis, autonomous prescribing, or regulatory certification out-of-the-box.
- Does not bypass tenant facility isolation, RBAC/ABAC method security, or Liquibase schema governance.

## 4. Domain Model
- JPA Entities: `IntegrationMessageLedgerEntity`, `IntegrationReplayAuditEntity`, `PartnerConformanceProfileEntity`
- Relational Tables: `hc_intops_message_ledger`, `hc_intops_partner_profiles`, `hc_intops_replay_audit`

## 5. Database Migrations
Sole migration authority: **Liquibase** (portable `${uuid_type}`, `${ts_type}`, `${clob_type}`, `${bool_type}` across PostgreSQL, SQL Server, and MySQL).
- `src/main/resources/db/changelog/integration_ops/001-integration-ops.xml`

## 6. REST APIs
- `GET /api/v1/integration-ops/messages`
- `GET /api/v1/integration-ops/partners/health`
- `GET /api/v1/integration-ops/partners`
- `PATCH /api/v1/integration-ops/partners/{partnerId}/circuit`
- `POST /api/v1/integration-ops/inbox`
- `POST /api/v1/integration-ops/messages/{messageId}/replay`
- `POST /api/v1/integration-ops/outbox`
- `POST /api/v1/integration-ops/partners/{partnerId}/replay-dlq`
- `POST /api/v1/integration-ops/partners`

## 7. FHIR or HL7 Mappings
- Standards footprint: DICOM, FHIR R4, HL7 v2, IHE, X12

## 8. Authorization Model
- Enforces Spring Security OIDC/OAuth2 resource-server authorities with zero `or isAuthenticated()` fallback bypasses:
- `hasAuthority('PERM_hie:interop') or hasAuthority('PERM_tenant:admin')`
- `hasAuthority('PERM_hie:interop')`

## 9. Tenant Scope
- Strictly scoped by authenticated `FacilityContext` (`facility_id`) and Hibernate `facilityFilter` / PostgreSQL Row-Level Security (RLS) policies where relational persistence is used.

## 10. Audit Events
- `INTOPS_CIRCUIT_`, `INTOPS_DELIVERED`, `INTOPS_DLQ_REPLAY`, `INTOPS_PARTNER_UPSERT`, `INTOPS_`

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
- `IntegrationOpsServiceTest` (unit/contract JUnit 5 test suite)

## 16. Known Limitations
- Live Maven (`mvn -B clean verify`) and multi-database Testcontainers execution require a JDK 25 + Docker CI runner; see repository `docs/KNOWN_LIMITATIONS_REGISTER.md` for global boundaries.
