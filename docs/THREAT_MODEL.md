# Threat Model — Opes HealthCore HIS Platform

> **Scope:** Application, API, persistence, interoperability, and Clinical AI boundaries across `healthcore-*` modules. This document does not substitute for an independent third-party penetration test or jurisdiction-specific security accreditation.

---

## 1. Assets Protected
1. **Protected Health Information (PHI) & PII:** Patient demographics, MPI identifiers, clinical notes, lab results, imaging studies, billing ledgers.
2. **Clinical Integrity:** Medication orders, FEFO pharmacy stock, lab QC release gates, radiology critical finding read-backs, NEWS2 escalations.
3. **Audit & Attestation Trails:** Tamper-evident hash-chained clinical audit logs and clinician AI sign-off records.
4. **Cryptographic & Integration Secrets:** OIDC signing keys, PII encryption keys, webhook HMAC secrets, PACS/DHIS2/Gemini API credentials.

## 2. Trust Boundaries
- **TB-1 (Public / Clinic Network → API Gateway / Spring Security):** Untrusted HTTP/REST, FHIR R4, IHE, HL7v2, and Patient Portal traffic. Enforced via stateless OAuth2/OIDC JWT validation, explicit `PERM_*` authorities, and rate limiting.
- **TB-2 (Tenant A ↔ Tenant B on Shared Database):** Enforced via `FacilityContext`, mandatory repository `facilityId` predicates, `TenantGovernanceGuardFilter`, and PostgreSQL transaction-local RLS (`app.current_facility_id`).
- **TB-3 (Hospital Core → External PACS / DHIS2 / Payers / Gemini AI):** Outbound boundaries protected by CircuitBreaker/Bulkhead resilience, Dead-Letter Queues, heuristic PHI redaction (for AI), and environment-injected credentials.
- **TB-4 (Clinician UI Session → Backend Services):** `/ui/**` Thymeleaf workstation isolated on its own `@Order(1)` Spring Security chain; API/FHIR routes strictly require bearer JWT tokens.

## 3. STRIDE Threat Analysis & Mitigations

| Threat Category | Scenario | Mitigation Implemented in Code | Residual Risk / Operational Control |
| :--- | :--- | :--- | :--- |
| **Spoofing** | Forged JWT or cross-facility `X-Facility-Id` header | OIDC JWT signature validation; `DicomPacsProxyService` & `HieInteropService` reject facility header mismatch with `403`. | Deploy behind TLS 1.3 with strict Keycloak issuer pinning and short-lived access tokens. |
| **Tampering** | Altering signed clinical notes, lab results, or audit logs | Immutable linked amendments (`supersedes_report_id` / `amended_from_result_id`), optimistic locking `@Version`, SHA-256 hash-chained `ClinicalAuditLogger`. | Ship audit logs to external WORM/SIEM storage for cryptographic independence. |
| **Repudiation** | Clinician denying order sign-off, critical read-back, or AI attestation | Mandatory actor/practitioner IDs + timestamps + fail-closed `ClinicalAuditPersistenceException` on mandatory AI audit failures. | Enforce MFA and individual (non-shared) clinician accounts in Keycloak. |
| **Information Disclosure** | Cross-tenant query via `findAll()`, PACS QIDO leak, or preliminary lab exposure to patients | Removed `isAuthenticated()` fallbacks & `findAll()` on HIE routes; QIDO-RS filters by tenant-owned UIDs; Patient Portal restricts results to `FINAL`/`AMENDED`; Analytics enforces k-anonymity cell suppression (`>= 5`) and 4-eyes cohort approval. | Enable PostgreSQL RLS (`enable-rls-policies.sql`) with non-superuser DB role in production. |
| **Denial of Service** | Flooded FHIR/PACS/AI endpoints or inventory race conditions | Bucket4j rate limiting, Resilience4j bulkheads & circuit breakers, pessimistic/conditional FEFO inventory locking. | Configure ingress WAF, connection pool bounds, and autoscaling alerts. |
| **Elevation of Privilege** | Authenticated non-clinical user calling FHIR/Admin routes or `dev-seed` running in prod | Least-privilege `PERM_*` `@PreAuthorize` rules; `ProductionSecretsStartupValidator` blocks startup if `dev-seed`, `allow-unregistered-in-dev`, or weak/default secrets appear in `prod`. | Perform periodic RBAC entitlement reviews and independent penetration testing before go-live. |
