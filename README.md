# Opes HealthCore SDK (`com.opeshealth.healthcore`)

**Author / Principal Architect:** Jude Nshome — **Opes Health Systems**  
**Email:** `nshome@opesware.com`  
**Website:** [https://www.opeshealthsystems.com](https://www.opeshealthsystems.com)  
**Location:** Douala, Cameroon  

---

## Engineering state and independent developer quickstart (21 November 2026)

**HealthCore is a library of independently reusable healthcare and life-science starters; it is not a deployable commercial HIS, EMR, LIS, pharmacy application, pharmaceutical MES or HIE.** The `healthcore-quickstart-host` and the 11 `examples/*` projects are reference consumers, not production products.

**Release status: PRE-PRODUCTION.** The `main` branch still contains failing CI; the [Java 25 repair PR #2](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/pull/2) adds a verified Java 25/Maven/PostgreSQL development container and numerous compilation repairs, but had not passed the full reactor and multi-DB workflows at the last documented check. No claim of complete workflow, clinical safety or regulatory certification is made.

### Choose a capability pack, not a hospital platform

| Build your own product | Use the corresponding reference consumer | What must be proven |
|---|---|---|
| Pharmacy | [`examples/standalone-pharmacy`](examples/standalone-pharmacy/) | Real persisted workflow and failure paths — [acceptance matrix](docs/verification/EXAMPLE_WORKFLOW_ACCEPTANCE_MATRIX.md) |
| Laboratory | [`examples/diagnostic-laboratory`](examples/diagnostic-laboratory/) | Real persisted workflow and failure paths — [acceptance matrix](docs/verification/EXAMPLE_WORKFLOW_ACCEPTANCE_MATRIX.md) |
| Radiology | [`examples/radiology-center`](examples/radiology-center/) | Real persisted workflow and failure paths — [acceptance matrix](docs/verification/EXAMPLE_WORKFLOW_ACCEPTANCE_MATRIX.md) |
| Pharma MES/QMS | [`examples/pharma-manufacturing`](examples/pharma-manufacturing/) | Real persisted workflow and failure paths — [acceptance matrix](docs/verification/EXAMPLE_WORKFLOW_ACCEPTANCE_MATRIX.md) |
| Life-science LIMS/ELN | [`examples/life-sciences-lims`](examples/life-sciences-lims/) | Real persisted workflow and failure paths — [acceptance matrix](docs/verification/EXAMPLE_WORKFLOW_ACCEPTANCE_MATRIX.md) |
| Biobanking | [`examples/biobank`](examples/biobank/) | Real persisted workflow and failure paths — [acceptance matrix](docs/verification/EXAMPLE_WORKFLOW_ACCEPTANCE_MATRIX.md) |
| Research/EDC | [`examples/clinical-research`](examples/clinical-research/) | Real persisted workflow and failure paths — [acceptance matrix](docs/verification/EXAMPLE_WORKFLOW_ACCEPTANCE_MATRIX.md) |
| Outpatient EMR | [`examples/outpatient-emr`](examples/outpatient-emr/) | Real persisted workflow and failure paths — [acceptance matrix](docs/verification/EXAMPLE_WORKFLOW_ACCEPTANCE_MATRIX.md) |
| Longitudinal EHR | [`examples/longitudinal-ehr`](examples/longitudinal-ehr/) | Real persisted workflow and failure paths — [acceptance matrix](docs/verification/EXAMPLE_WORKFLOW_ACCEPTANCE_MATRIX.md) |
| Hospital HIS | [`examples/hospital-his`](examples/hospital-his/) | Real persisted workflow and failure paths — [acceptance matrix](docs/verification/EXAMPLE_WORKFLOW_ACCEPTANCE_MATRIX.md) |
| Regional HIE | [`examples/regional-hie`](examples/regional-hie/) | Real persisted workflow and failure paths — [acceptance matrix](docs/verification/EXAMPLE_WORKFLOW_ACCEPTANCE_MATRIX.md) |

### Run and verify code

1. Use **Java 25** with Maven 3.9+, PostgreSQL 16 and Docker/Testcontainers. On [repair PR #2](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/pull/2), VS Code → **Dev Containers: Reopen in Container** provisions a Java/Maven/PostgreSQL environment; see `docs/DEVELOPMENT_ENVIRONMENT_JAVA25.md` on that branch.
2. Build the reactor with `mvn -B -ntp -fae clean verify`. A successful module compile does not imply a successful independent product workflow.
3. For each consumer, run its *independent* Maven + Testcontainers integration tests; see [workflow acceptance matrix](docs/verification/EXAMPLE_WORKFLOW_ACCEPTANCE_MATRIX.md). **Existing `WorkflowSmokeTest.java` files only check class presence. They are not end-to-end tests.**
4. Run `python3 scripts/check_example_workflow_evidence.py` to report structural workflow-test gaps. Use `--enforce` only as an additional release gate; it will fail until real tests are implemented and does not itself execute them.
5. Review the [20 scoped engineering workstreams](docs/agents/HEALTHCORE_20_WORKSTREAMS.md) and [issues #3–#22](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/issues). These issues are **created, but no coding agents have been confirmed running** because GitHub rejected the Copilot bot assignment.

### Running a real clinical and standalone workflow verification

A classpath-only smoke test is **not** a working clinical application. The full [clinical and standalone execution playbook](docs/verification/CLINICAL_STANDALONE_EXECUTION_PLAYBOOK.md) defines the patient journey (MPI → triage → financial policy → consultation → parallel lab/imaging/pharmacy → clinician result acknowledgment → safe disposition), plus role/tenant, duplicate-delivery, critical-result, persistence and restart failure cases.

To verify all 11 independently composable examples, rather than count the shared hospital host or mock tests:

```bash
bash scripts/run_standalone_workflow_verification.sh
```

This **fails closed today** because the independent application workflow ITs have not been completed. When implemented, the runner must build the SDK, execute each real PostgreSQL workflow IT, and inspect fresh Maven Failsafe XML to reject zero-test or skipped-test results. A green structural check or a mere compile does **not** confer clinical or regulatory certification. The [standalone release Actions workflow](.github/workflows/standalone-consumer-release-gate.yml) applies the same gate. Use only synthetic data and keep clinical review separate from automated verification.

**Security:** The `X-Facility-Id` header is **not proof of authorization**. All facility-scoped work must derive trusted identity and membership from verified credentials; use explicit negative tenant tests. Clinical data exports and de-identification require governance and independent risk review. Never use identifiable patient data in examples.

**Acceptance before publishing any SDK starter:** isolated consumer startup, deterministic schema migrations, authenticated domain transaction, real operational/clinical lifecycle, negative safety checks, event/idempotency/restart behavior, documentation linked to a green CI run, and appropriate clinical/regulatory signoff when applicable.

---

## Overview

**Opes HealthCore** is **not** a single-purpose end-user application — it is a **Java 25 + Spring Boot 4.0** (plus **Kotlin/Compose Multiplatform** for Android, iPhone & iPad) reusable modular starter SDK. Teams can select modules to compose their own software, subject to real integration and verification:

- **Full Hospital EMR / EHR** (10-stage clinical workflow: Appointments -> ESI Triage -> LOINC Vitals & NEWS2 -> Immutable SOAP Notes w/ Co-Sign & License Gate -> CDSS-gated CPOE -> Bed ADT & Fluid I&O/Handover/MedRec -> 5-Rights eMAR -> Discharge -> CarePlans -> Referrals)
- **Standalone Pharmacy Information System (PIS)** (`healthcore-starter-pharmacy-inventory` mounts ONLY FEFO inventory, expiry quarantine & dual-witness controlled dispensing without LIS routes)
- **Standalone Diagnostic Lab Information System (LIS)** (`healthcore-starter-lis-diagnostics` mounts ONLY barcode accession, Westgard QC gate, reflex & panic escalation)
- **Specialized Clinic Systems** (`Dental`, `Eye/Ophthalmology`, `Cardiac`, `Pediatrics`, `Dermatology` via `SpecialtyPackSpi`)
- **Regional / National HIE** (`healthcore-starter-fhir-hie`, `healthcore-starter-hl7-astm-dicom`, `healthcore-starter-interoperability-ihe`: FHIR R4 IPS `$summary`, Bulk NDJSON `$export`, Transaction Bundle, IHE PIXm/PDQm/MHD/mCSD, HL7v2 MLLP TCP port 2575, ASTM E1394, DICOMweb `QIDO/STOW/WADO-RS`, CDA R2)
- **Universal Health Care (UHC / CBHI), CHW, Home Care & IDSR Disease Watch** (OpenIMIS FHIR `/Coverage` & `/Claim`, Health Vouchers, Signed Offline UHC Eligibility QR, MTN MoMo / Orange Money HMAC, CHW MUAC/ANC/iCCM, IDSR CIF + Malaria/HIV/TB/Hepatitis Cascades + DHIS2 ADX XML)
- **Offline-First Android, iPhone & iPad Companion Suite** (`healthcore-mobile-kmp`: Android SQLCipher + Keystore + WorkManager + Camera QR, iPhone/iPadOS Keychain SQLite + Apple Pencil + BGTaskScheduler, HLC + `sync_seq`, SMART PKCE discovery)

> **Strict Technology Guarantee:** 0% TypeScript / JavaScript / Node.js. Maven Enforcer + ArchUnit enforce Java 25 and zero TypeScript artifacts.

---

## Module Architecture

### Core & Composite Starters
| Module | Purpose |
| :--- | :--- |
| `healthcore-bom` | Centralized Bill of Materials (Spring Boot 4.0, Java 25, HAPI FHIR 8, Liquibase, Resilience4j, Caffeine, Redis, Kafka, POI, PG/MSSQL/MySQL) |
| `healthcore-persistence-core` | RFC 9562 UUIDv7, CQRS contracts, Transactional Outbox (`SKIP LOCKED` & MSSQL `READPAST`), optional Redis L2 cache & Kafka outbox, Hibernate `facilityFilter`, PostgreSQL `PostgresRlsSessionAspect`, RFC 7807 `ProblemDetail` + FHIR `OperationOutcome`, `X-Idempotency-Key` filter |
| `healthcore-security-audit` | Keycloak/Auth0 JWT + SMART on FHIR v2 (`/.well-known/smart-configuration`), 18-role `@PreAuthorize` RBAC, ABAC sensitivity (`HIV`/`PSYCH`/`VIP`) + Break-the-Glass, SHA-256 hash-chained audit, AES-256-GCM PII vault, Logback `PhiRedactionConverter`, `WorkforceCredentialSpi` |
| `healthcore-starter-workflow-engine` | Facility-customizable EMR/EHR/HIE state-machine orchestrator, 8 reusable clinical/PIS/LIS/OT/CHW blueprints, pluggable `WorkflowStepHookSpi` & `WorkflowSlaEscalationSpi`, SLA watch scheduler, PostgreSQL GIN/partial indexes |
| `healthcore-starter-terminology-cdss` | LOINC, SNOMED CT (`hc_concept_closure` IS-A), ICD-10/11, RxNorm/ATC, UCUM, bulk CSV/RF2/FHIR CodeSystem importer, CDS Hooks 2.0 (`order-sign`, `order-select`, `encounter-start`, `patient-view`, Renal eGFR <60/<30, Pediatric mg/kg, NEWS2/Lab Panic) |
| `healthcore-starter-mpi` | Master Patient Index Golden Record, Phonetic + Fellegi-Sunter deduplication, reversible unmerge + merge event cascade, FHIR R4 `/Patient` with `If-Match` ETag `412` protection |
| `healthcore-starter-emr-clinical` | Full 10-stage clinical workflow + Bed transfers (`ADT^A02`), Fluid I&O, Shift Handovers, Med Reconciliation, Referrals, Co-Sign & License Expiry gate, and 5 Specialty Packs |
| `healthcore-starter-pharmacy-lis` | Combined PIS + LIS module (or pick standalone starters below) |
| `healthcore-starter-hie-interop` | Combined HIE module including live Virtual-Thread HL7v2 MLLP TCP server + Resilience4j-guarded FHIR Subscription webhooks |
| `healthcore-starter-uhc-billing` | Integer minor-unit double-entry ledger, CBHI / OpenIMIS FHIR `/Coverage` & `/Claim`, Health Vouchers, Signed Offline UHC Eligibility QR, MTN MoMo & Orange Money HMAC webhooks |
| `healthcore-starter-public-health` | Combined CHW/Home Care, EPI & Program Cascades, and IDSR Disease Watch |
| `healthcore-starter-hospital-ops` | Combined Blood Bank, OT, Telemedicine, Cold-Chain IoT, CRVS/Mortuary, Workforce Roster & Safe-Harbor De-ID |
| `healthcore-starter-offline-sync` | Hybrid Logical Clock (`HLC`), `sync_seq` delta pull/push, clinical conflict queue, Ed25519 QR IPS Health Card |
| `healthcore-starter-templates-dashboards` | **EN/FR hospital template catalog (individual form completeness and translations require verification)**, `QuestionnaireResponse`, OpenHTMLtoPDF PDF/A, **7 live SQL CQRS dashboards**, Apache POI XLSX + CSV bulk import/export, Cascading Facility Onboarding, concrete PG/MSSQL/MySQL backup & PITR/Edge DR |
| `healthcore-starter-api-product` | OpenAPI 3.1 contract, API keys, sliding-window rate limiter (`429 Retry-After`), HMAC webhook signer |
| `healthcore-starter-clinical-ai` | Server-side Gemini Clinical AI gateway (`gemini-4-hydrogen`, `gemini-3.5-transcribe`, `gemini-3.8-flash-lite-tts`, `gemini-embedding-2`, `gemini-decision-1`), heuristic PHI risk-reduction pre-filter, durable JPA-backed tenant kill-switch, clinician attestation drafts, and fail-closed SHA-256 hash-chained audit logs |
| `healthcore-starter-tenant-governance` | Multi-tenant organization/facility/department hierarchy, facility onboarding/read-only/suspension/offboarding lifecycle enforcement (`TenantGovernanceGuardFilter`), durable feature flags & kill switches, and time-bound cross-facility access grants |
| `healthcore-starter-scheduling` | Provider/room/equipment/clinic calendars, appointment types, overlap & overbooking conflict detection (`409`), recurring series, outpatient check-in queues, waitlists, and FHIR R4 `Schedule`/`Appointment` projections |
| `healthcore-starter-orders-results` | Unified common clinical order engine (lab, imaging, medication, procedure, referral), reusable order sets, specimen accessioning, immutable result amendment lineage, mandatory critical-value closed-loop acknowledgment, non-recursive reflex rules, and FHIR R4 `ServiceRequest`/`DiagnosticReport` projections |
| `healthcore-starter-patient-portal` | Patient-scoped and proxy-authorized portal APIs for appointments, verified-only (`FINAL`/`AMENDED`) lab results, multilingual locale preferences, and secure patient-to-care-team messaging |
| `healthcore-starter-radiology-ris` | DICOM Modality Worklist (MWL/MPPS), STAT radiology scheduling, structured reports, immutable addenda, and closed-loop critical finding verbal read-back |
| `healthcore-starter-emergency-nursing` | Emergency Department ESI 1–5 triage board, nurse-to-patient ward ratios, RCP NEWS2 escalation, and 5-gate discharge readiness checklist |
| `healthcore-starter-analytics-quality` | Operational hospital KPIs, Clinical Quality Measures (eCQM), and 4-eyes k-anonymity cohort exports with SHA-256 lineage digests |
| `healthcore-starter-integration-ops` | Trading partner conformance registry, persistent message delivery ledger, idempotent outbox, circuit-breaker health, and auditable DLQ replay governance (`/api/v1/integration-ops`) |
| `healthcore-starter-revenue-coding-claims` | Facility tariff schedules, clinician-attested ICD-10/11/CPT/DRG coding, insurance eligibility & prior authorization, and X12/FHIR payer claims + ERA 835 remittance (`/api/v1/revenue-claims`) |
| `healthcore-quickstart-host` | Reference Spring Boot 4.0 + PostgreSQL 16+ host with **17 Pure Java Spring MVC + Thymeleaf Reference Hospital Consoles** (`/ui/overview` through `/ui/revenue-claims`), `dev-seed` demonstration hospital seeder, Liquibase multi-RDBMS master changelog (38 includes), `enable-rls-policies.sql`, Keycloak realm, Logback PHI redaction, hardened Java 25 `Dockerfile`, DR scripts (`scripts/`), ArchUnit & Testcontainers multi-DB suite |
| `healthcore-mobile-kmp` | Pure Kotlin + Compose Multiplatform Android (SQLCipher/WorkManager/Camera QR) + iPhone/iPadOS (Keychain/Apple Pencil/BGTaskScheduler) companion foundation with Vitals/NEWS2, 5-Rights eMAR, Encrypted Chunked Media Sync, Offline Conflict Queue, and Device Revocation Lockout |

### Granular Composable Standalone Starters
Pick only the exact slices your facility or SaaS product requires:
- `healthcore-starter-pharmacy-inventory` (PIS only)
- `healthcore-starter-lis-diagnostics` (LIS only)
- `healthcore-starter-blood-bank` (Donor Hb screening, ISBT-128, 2-person bedside verify, hemovigilance)
- `healthcore-starter-surgery-ot` (OT scheduling, WHO Surgical Safety Checklist gate, Anesthesia & PACU Aldrete)
- `healthcore-starter-telemedicine` (WebRTC & 3G-fallback virtual care)
- `healthcore-starter-biomedical-coldchain` (2°C–8°C cold-chain MQTT webhook & Biomed PM calibration)
- `healthcore-starter-crvs-mortuary` (Births, WHO MCCD Cause-of-Death & Mortuary chain-of-custody)
- `healthcore-starter-workforce-roster` (Fail-closed practitioner license expiry gate & shift rosters)
- `healthcore-starter-deidentification-research` (Structured/heuristic de-identification helpers — institutional privacy review still required)
- `healthcore-starter-chw-homecare` (CHW households, ANC/iCCM/MUAC referrals & Home Care RPM)
- `healthcore-starter-programs-immunization` (WHO EPI immunization & HIV ART/TB DOT/Hepatitis cascades)
- `healthcore-starter-disease-surveillance` (IDSR CIF, outbreak watch, contact tracing & DHIS2 ADX)
- `healthcore-starter-fhir-hie` (FHIR R4 CapabilityStatement, partial IPS `$summary`, Bulk NDJSON `$export`, Transaction/Batch Bundle, Subscriptions)
- `healthcore-starter-hl7-astm-dicom` (HL7v2 ADT ingestion + MLLP TCP/HTTP, ASTM E1394 parser, DICOM study metadata registry + optional external PACS proxy, CDA R2)
- `healthcore-starter-interoperability-ihe` (IHE PIXm, PDQm, MHD `DocumentReference`, DB-backed mCSD `Organization`/`Location`)

---

## Scope, Integration Defaults & Compliance Notices

### 1. Pure Java Reference Hospital Workstation & Backend SDK
This repository provides **Java 25 / Spring Boot 4.0 reusable starters**, an **OpenAPI 3.1 Swagger UI** (`/swagger-ui.html`), a **Pure Java Spring MVC + Thymeleaf Reference Hospital Workstation** (`/ui/overview`, zero TypeScript/Node.js) wired to real starter services, and a **Kotlin/Compose Multiplatform mobile foundation** (`healthcore-mobile-kmp`). Launch with `-Dspring-boot.run.profiles=postgres,keycloak,dev-seed` to seed the demonstration hospital (`Douala Reference Hospital`) cleanly outside production.

### 2. Optional Integrations Disabled by Default
Core persistence, RBAC/ABAC security, auditing, and clinical REST/FHIR APIs work out of the box with a relational database and OIDC provider. The following external integrations are **optional extension points disabled by default** in `application.yml` until explicitly configured and enabled:
- **External PACS / Binary DICOMweb (`healthcore.hie.dicom.pacs-enabled=false`)**: Built-in `/api/v1/hie/dicom/studies` acts as a tenant-scoped DICOM study metadata registry. Binary STOW-RS / WADO-RS object storage fails with HTTP `501 Not Implemented` until an external Orthanc/DCM4CHEE DICOMweb server is configured.
- **PostgreSQL RLS (`healthcore.postgres.rls.enabled=false`)**: Application-level tenant isolation (`FacilityContext` + mandatory tenant-scoped repository queries) is enforced by default; database-level PostgreSQL RLS requires running `enable-rls-policies.sql` with a non-superuser role and setting `enabled: true`.
- **Event Brokers & External Feeds**: Apache Kafka (`healthcore.outbox.kafka.enabled=false`), RabbitMQ (`healthcore.outbox.rabbitmq.enabled=false`), Redis L2 Cache (`healthcore.cache.redis.enabled=false`), Cold-Chain MQTT (`healthcore.coldchain.mqtt.enabled=false`), DHIS2 Sync (`healthcore.dhis2.enabled=false`), External FHIR Terminology Sync (`healthcore.terminology.remote-sync.enabled=false`), and HL7v2 MLLP TCP listener (`healthcore.hie.mllp.enabled=false`).

### 3. Standards Conformance & Clinical Safety Boundaries
- **FHIR R4 CapabilityStatement & Transaction/Batch**: Advertises only implemented interactions (`Patient` search + `$summary`/`$export`/`$ihe-pix`, `DocumentReference` create/search, `Subscription` create, `Organization`/`Location` search). Transaction/Batch bundles execute real persistence for `POST`/`PUT` `Patient` and `POST` `DocumentReference` (rolling back atomically on `TRANSACTION` error and returning `501 Not Implemented` `OperationOutcome` for unsupported `BATCH` entries).
- **IPS Summary (`$summary`)**: Assembles persisted tenant-scoped `Patient`, `DocumentReference`, and `ImagingStudy` resources and marks unpopulated clinical sections (Medications, Allergies, Problems) with FHIR `emptyReason: notasked` (`status: preliminary`). Full multi-module IPS clinical section validation against HL7 IPS profiles requires additional clinical profile conformance testing.
- **HL7v2 Ingestion**: Tenant-scoped `ADT^A01`, `ADT^A04`, and `ADT^A08` messages map PID demographics into `PatientAggregateEntity` with `MSH-10` deduplication (`AA`). Unsupported HL7 message types (`ORU`, `ORM`, `SIU`, `DFT`, etc.) are explicitly rejected with `AR` and routed to the integration DLQ.
- **Heuristic PHI Redaction Pre-Filter (`PhiRedactionGateway`)**: Context-aware + regex scrubbing reduces direct identifier exposure before AI calls, but **regex scrubbing alone is NOT a certified standalone HIPAA Safe Harbor (§ 164.514(b)(2)) de-identification guarantee** for arbitrary unstructured clinical text. Deployments must maintain a BAA, institutional privacy review, and clinician attestation.
- **Clinical Rules Validation**: Deterministic RCP NEWS2 Scale 1 scoring, ABO/RhD PRBC/FFP blood compatibility checks, 5-Rights eMAR verification, and CPOE CDSS guardrails require independent clinical domain sign-off and local formulary/terminology configuration before live patient care.

### 4. Schema Migration Ownership & Externalized Secrets
- **Liquibase is the sole authoritative schema migration owner** (`spring.liquibase.enabled: true`) across PostgreSQL 16+, MSSQL 2022+, and MySQL 8.4+. Legacy Flyway dependencies, configuration, and SQL folders have been removed to guarantee a single deterministic schema history.
- All signing secrets (`HEALTHCORE_HIE_WEBHOOK_SECRET`, `HEALTHCORE_UHC_QR_SIGNING_SECRET`, `HEALTHCORE_UHC_MOMO_WEBHOOK_SECRET`, `HEALTHCORE_PII_AES_KEY_B64`, `HEALTHCORE_PII_HMAC_KEY_B64`) are externalized (see `.env.example`) and enforced at boot by `ProductionSecretsStartupValidator` whenever `prod` or `production` profiles are active (which also aborts boot if `dev-seed` is accidentally enabled).

---

## Build, Test & CI Verification (Requires JDK 25 + Maven 3.9+ + Docker)

Automated CI is defined in [`.github/workflows/ci.yml`](.github/workflows/ci.yml) using **Temurin JDK 25** (`mvn -B -ntp clean verify -DskipITs=true`) plus static security guardrails.

> **Note:** Local build and Testcontainers execution require an environment with **JDK 25**, **Maven 3.9+**, **Gradle 8.10+**, and **Docker** installed.

```bash
# 1. Start PostgreSQL 16 + Keycloak 26 (or add --profile all-dbs for MSSQL 2022 & MySQL 8.4)
docker compose up -d

# 2. Run Unit, Regression & ArchUnit Governance Tests (Java 25 required)
mvn clean test

# 3. Run Testcontainers Multi-DB Liquibase Parity Suite (PostgreSQL + MSSQL + MySQL)
mvn test -Dgroups=multidb-it

# 4. Launch Quickstart Host with Reference Hospital UI + Demo Hospital Seed (Port 3000 -> http://localhost:3000/ui/overview)
mvn -pl healthcore-quickstart-host spring-boot:run -Dspring-boot.run.profiles=postgres,keycloak,dev-seed

# Optional profiles:
#   -Dspring-boot.run.profiles=postgres,keycloak           (Production-like without demo seed)
#   -Dspring-boot.run.profiles=mssql,keycloak              (Windows SQL Server)
#   -Dspring-boot.run.profiles=mssql-integrated,keycloak   (Windows AD Integrated Auth)
#   -Dspring-boot.run.profiles=mysql,keycloak              (MySQL 8.4)
#   -Dspring-boot.run.profiles=postgres,keycloak,mtls      (Mutual TLS X.509)
#   -Dhealthcore.hie.mllp.enabled=true                     (Enable HL7v2 MLLP TCP :2575)
```

---

## Developer Guide (EMR, EHR, HIE & Offline Mobile)

For step-by-step Maven BOM assembly recipes, multi-RDBMS setup, Keycloak/RLS security configuration, end-to-end cURL clinical playbooks, KMP `DataSyncManager` integration, and a production go-live checklist, see the full **[EMR, EHR & HIE Developer Guide](docs/DEVELOPER_GUIDE.md)**.

### Enterprise Digital Health & National HIE Services Included in `healthcore-quickstart-host`
- **SMART Health Links (`shlink:/`)**: AES-256-GCM encrypted manifests, passcodes, expiry, max-access limits, and revocation (`/api/v1/shl/**`)
- **FHIR Chart Portability (`$everything`)**: `Patient/{id}/$everything`, `Encounter/{id}/$everything`, and Patient Compartment search with DS4P enforcement
- **Federated HIE Trust (UDAP / TEFCA / IHE XCPD & XCA)**: X.509 Trust Anchors, UDAP Dynamic Client Registration, Cross-Community Patient Discovery & Document Query/Retrieve (`/api/v1/hie/**`)
- **Clinical Quality Measures (eCQM / CQL)**: Standard CMS/HEDIS/WHO `Measure` catalog & `$evaluate-measure` FHIR `MeasureReport` generation (`/api/v1/fhir/Measure/**`)
- **FHIR Consent, DS4P Privacy & Break-the-Glass**: Fine-grained `HIV`/`PSY`/`ETH`/`SDV`/`GEN`/`VIP` segmentation with mandatory compliance audit flagging (`/api/v1/consent/**`, `/api/v1/fhir/Consent/**`)
- **JWS-Signed IPS (`$summary`) & Digital Health Certificates**: `RS256` & `Ed25519` JWS signing, SMART Health Card (`shc:/`) SVG/PNG QR codes, verification, and RFC 7517 JWKS (`/api/v1/ips/**`, `/api/v1/certificates/**`)
- **Omnichannel Patient Messaging & Recall**: Bilingual EN/FR `SMS`/`WHATSAPP`/`USSD`/`PUSH` notifications & interactive feature-phone USSD menus (`/api/v1/messaging/**`)
- **Longitudinal CarePlans & Care-Gap Closure**: Diabetes, Hypertension, HIV ART, TB DOT, Maternal ANC, and Heart Failure pathways (`/api/v1/care-plans/**`)
- **Radiology Modality Worklist (DICOM MWL/MPPS) & Critical Findings Escalation**: Closed-loop radiology safety acknowledgment (`/api/v1/radiology/**`)
- **Clinical Research, Dynamic eConsent & OMOP CDM v5.4 Export**: Trial eligibility cohort builder & OMOP export helpers (`/api/v1/research/**`)
- **Resumable Chunked Encrypted Offline Media Sync**: SHA-256-verified binary attachment uploads paired with KMP `DataSyncManager` (`/api/v1/sync/media/**`)
- **Payer Clearinghouse & EDI X12 (`837`/`835`)**: Automated claim scrubbing, remittance reconciliation, FHIR `ExplanationOfBenefit`, and denial appeals (`/api/v1/clearinghouse/**`)
- **Integration DLQ & Webhook/MLLP Replay**: Operational dead-letter inspection, single/bulk replay, and circuit-breaker health (`/api/v1/ops/dlq/**`)
- **Multi-Tenant SaaS Onboarding & Feature-Flag Governance**: Self-service facility onboarding, module licensing, terminology version pinning, and per-tenant feature flags (`/api/v1/admin/tenants/**`)
- **Clinical & Operational AI Suite (`healthcore-starter-clinical-ai`)**: Ambient Scribe & Voice-to-SOAP (`/api/v1/ai/scribe/**`), Automated Medical Coding (`/api/v1/ai/coding/**`), DS4P-Aware Chart & IPS Summarizer (`/api/v1/ai/summaries/**`), Multimodal Image Triage (`/api/v1/ai/vision/**`), Advisory Clinical Decision Support (`/api/v1/ai/cds/**`), Prior-Auth & Claim Denial Appeal Writer (`/api/v1/ai/revenue/**`), Calibrated `gemini-decision-1` Triage & Policy Engine (`/api/v1/ai/decisions/**`), Multilingual Text+Voice Discharge Coach (`/api/v1/ai/coach/**`), Semantic Chart & Trial Cohort Search (`/api/v1/ai/semantic/**`), and Heuristic PHI Redaction + Durable Clinician Attestation Governance (`/api/v1/ai/governance/**`)

OpenAPI 3.1 Swagger UI is served at `http://localhost:3000/swagger-ui.html`.
