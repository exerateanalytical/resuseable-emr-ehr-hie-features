# Architecture Decision Records (ADRs)

This document records the core architectural decisions governing the Opes HealthCore Hospital Information System (HIS) platform (`com.opeshealth.healthcore`).

---

## ADR-001: Pure Java 25 + Spring Boot 4.0 Modular Starter Architecture
- **Status:** Accepted
- **Context:** Hospital deployments vary widely — some need only a Pharmacy or Laboratory system, while tertiary hospitals require full EMR, Inpatient Nursing, Radiology RIS, Billing, Interoperability, and Analytics.
- **Decision:** Package every major domain as an independent Spring Boot 4.0 auto-configured starter (`healthcore-starter-*`) managed via a single BOM (`healthcore-bom`) and composed into a reference host (`healthcore-quickstart-host`). Enforce Pure Java/JVM builds via Maven Enforcer (no Node.js or TypeScript in the backend repository); build the Reference Hospital Web UI using server-rendered Spring MVC + Thymeleaf.
- **Consequences:** Single-toolchain builds, composable deployments, zero frontend supply-chain sprawl. Rich interactive offline mobile workflows are delegated to the separate Kotlin Multiplatform project (`healthcore-mobile-kmp`).

## ADR-002: Liquibase as Sole Authoritative Multi-RDBMS Migration Engine
- **Status:** Accepted (supersedes legacy dual Flyway/Liquibase setup)
- **Context:** Running both Flyway and Liquibase against PostgreSQL, Microsoft SQL Server, and MySQL caused schema drift and ambiguous migration ownership.
- **Decision:** Remove Flyway completely (dependencies, Java config, SQL scripts, and YAML properties). Use Liquibase exclusively with portable cross-DB type aliases (`${uuid_type}`, `${ts_type}`, `${clob_type}`, `${bool_type}`) aggregated by `db.changelog-master.xml`.
- **Consequences:** Single reproducible schema lineage across PostgreSQL 16+, SQL Server 2022+, and MySQL 8.4+.

## ADR-003: Defense-in-Depth Multi-Tenant Facility Isolation
- **Status:** Accepted
- **Context:** Cross-facility PHI leakage is a P0 patient-safety and privacy hazard. Relying solely on optional HTTP headers or disabled database policies is insufficient.
- **Decision:** Enforce tenant isolation at three independent layers:
  1. **Security/Request Layer:** `FacilityTenantInterceptor` extracts authenticated tenant claims and binds `FacilityContext`; `TenantGovernanceGuardFilter` enforces tenant lifecycle (`SUSPENDED` → `403`, `READ_ONLY` writes → `503`).
  2. **Service & Repository Layer:** Every query requires explicit `facilityId` scoping (zero `findAll()` on clinical tables) and rejects header/token mismatches.
  3. **Database Layer (PostgreSQL RLS):** `PostgresRlsSessionAspect` binds `app.current_facility_id` via transaction-local `set_config(..., true)`, binds `''` fail-closed when absent, and explicitly clears it in a `finally` block.
- **Consequences:** Strong isolation even against pooled-connection reuse; requires operators to apply `postgres/enable-rls-policies.sql` with a non-superuser role before flipping `healthcore.postgres.rls.enabled=true`.

## ADR-004: Fail-Closed Clinical Safety & Credential Controls
- **Status:** Accepted
- **Context:** Silent pass-through of expired credentials, empty allergy/medication safety context, or swallowed audit failures creates severe patient-safety risk.
- **Decision:**
  - Practitioner license/credential checks fail closed in production (`allow-unregistered-in-dev` is blocked by `ProductionSecretsStartupValidator` in `prod`/`production`).
  - Clinical AI drafts are durably persisted in `DRAFT_PENDING_ATTESTATION` status and require explicit practitioner sign-off; mandatory AI audit persistence failures throw `ClinicalAuditPersistenceException`.
  - External PACS DICOMweb STOW-RS/WADO-RS fail with explicit `501 NOT IMPLEMENTED` when `pacs-enabled=false` rather than returning fabricated success.

## ADR-005: Deterministic Clinical Scoring & Human-in-the-Loop AI
- **Status:** Accepted
- **Context:** Autonomous clinical diagnosis or prescribing is outside safe software boundaries and regulatory clearance.
- **Decision:** Deterministic clinical scoring (ESI 1–5 triage, NEWS2 early-warning vitals, Westgard QC, drug interaction rules) is separated from probabilistic LLM capabilities (`healthcore-starter-clinical-ai`). All AI outputs remain clinician-attested drafts; PHI redaction is documented strictly as a heuristic risk-reduction control, never as guaranteed HIPAA Safe Harbor de-identification.
