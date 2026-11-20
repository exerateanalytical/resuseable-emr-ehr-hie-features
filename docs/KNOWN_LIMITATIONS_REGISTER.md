# Known Limitations & Release Readiness Register

> **Current Release Readiness Status:** **PRE-PRODUCTION / ENGINEERING BUILD — NOT YET CLEARED FOR UNSUPERVISED CLINICAL PRODUCTION.**
>
> Per `HOSPITAL_INFORMATION_SYSTEM_COMPLETE_SPEC.json`, no production release is permitted until all P0/P1 findings have live CI test execution evidence, multi-DB Testcontainers pass, and independent security + clinical reviews are signed off.

---

## 1. Execution & Verification Limitations
1. **No Local Maven / Docker Execution in AI Build Container (`BUILD-001`):**
   - Source code, Liquibase XMLs, auto-configuration wiring, and JUnit 5 tests have been written and statically verified via Python AST/lexer/XML scripts.
   - Full `mvn clean verify`, Surefire unit test execution, and `MultiDbTestcontainersIT` (PostgreSQL 16+, SQL Server 2022+, MySQL 8.4+) **must be executed in a JDK 25 + Docker CI runner** (`.github/workflows/ci.yml`).
2. **Kotlin Multiplatform Mobile Build (`healthcore-mobile-kmp`):**
   - Contains 12 Kotlin source files, but a full Gradle wrapper + Android/iOS encrypted SQLCipher & offline conflict-resolution test suite has not yet been executed in CI.

## 2. Architectural & Interoperability Limitations
1. **PostgreSQL Row-Level Security (RLS) Opt-In (`SEC-002`):**
   - `PostgresRlsSessionAspect` now enforces fail-closed binding and post-transaction cleanup, but `healthcore.postgres.rls.enabled` remains `false` by default in `application.yml` until operators apply `postgres/enable-rls-policies.sql` using a non-superuser runtime DB role.
2. **External PACS Required for Binary DICOMweb (`DICOM-001`):**
   - `DicomPacsProxyService` strictly validates DICOM UIDs, rejects empty payloads, scopes QIDO-RS to tenant-owned studies, and fails closed with `501 NOT IMPLEMENTED` when `healthcore.hie.dicom.pacs-enabled=false`. Live binary STOW-RS/WADO-RS conformance requires connecting an external Orthanc or DCM4CHEE PACS instance.
3. **HL7 v2 & FHIR R4 Profile Conformance (`FHIR-001`, `FHIR-002`, `HL7-001`):**
   - Live HAPI FHIR StructureDefinition validator runs and partner-specific HL7 v2 ADT/ORM/ORU/SIU/DFT interface certification tests remain required before connecting external hospital systems.
4. **Thin Wrapper Starters vs. Full Standalone Starters:**
   - Core workflows in Blood Bank, Surgery/OT, Biomedical/Cold Chain, Workforce Roster, CRVS/Mortuary, and Telemedicine are currently persisted inside `healthcore-starter-hospital-ops` while their granular `healthcore-starter-*` modules act as thin composition wrappers.

## 3. Privacy, AI & Clinical Safety Limitations
1. **Heuristic PHI Redaction (`AI-002`):**
   - `PhiRedactionGateway` is a heuristic risk-reduction pre-filter and **does not guarantee HIPAA Safe Harbor de-identification** for arbitrary free-text indirect identifiers. Use only with enterprise zero-data-retention / BAA-covered AI endpoints and clinician review.
2. **Clinical Governance Review Required:**
   - NEWS2 early-warning thresholds (Scale 1 only; hypercapnic COPD Scale 2 requires local clinical configuration), ESI 1–5 triage rules, drug interaction rules, Westgard QC rules, and blood compatibility checks require independent hospital clinical governance sign-off before live patient care.
3. **No Certification Claimed:**
   - This repository makes no claim of HIPAA, GDPR, ISO 27001, ONC, CE, or FDA regulatory certification.

## 4. Research export caveat (PR #2)
- The de-identification starter provides HMAC-based pseudonymization and equivalence-class suppression. It **does not certify HIPAA Safe Harbor, GDPR anonymization, or legal release clearance**. Raw free-text and region prefixes are suppressed by default pending independent policy and re-identification-risk review. A short or publicly known HMAC key is not suitable for live data; store a high-entropy study-specific key outside source control, with consent, access, retention and provenance controls.
