# Opes HealthCore SDK (`com.opeshealth.healthcore`)

**Author / Principal Architect:** Jude Nshome — **Opes Health Systems**  
**Email:** `nshome@opesware.com`  
**Website:** [https://www.opeshealthsystems.com](https://www.opeshealthsystems.com)  
**Location:** Douala, Cameroon  

---

## Overview

**Opes HealthCore** is **not** a single-purpose end-user application — it is a **100% Pure Java 25 + Spring Boot 4.0** (plus **Kotlin/Compose Multiplatform** for Android, iPhone & iPad) reusable modular starter SDK. Teams can assemble any clinical or public-health platform in under 1 hour:

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
| `healthcore-starter-templates-dashboards` | **608 bilingual (EN/FR) authentic hospital templates**, `QuestionnaireResponse`, OpenHTMLtoPDF PDF/A, **7 live SQL CQRS dashboards**, Apache POI XLSX + CSV bulk import/export, Cascading Facility Onboarding, concrete PG/MSSQL/MySQL backup & PITR/Edge DR |
| `healthcore-starter-api-product` | OpenAPI 3.1 contract, API keys, sliding-window rate limiter (`429 Retry-After`), HMAC webhook signer |
| `healthcore-starter-clinical-ai` | Server-side Gemini Clinical AI gateway (`gemini-4-hydrogen`, `gemini-3.5-transcribe`, `gemini-3.8-flash-lite-tts`, `gemini-embedding-2`, `gemini-decision-1`), mandatory HIPAA Safe-Harbor 18-identifier PHI redaction, tenant kill-switch, and clinician attestation ledger |
| `healthcore-quickstart-host` | Reference `<1 hour` Spring Boot 4.0 + PostgreSQL 16+ host, `pg_trgm` GIN & partial index changelog, `enable-rls-policies.sql`, Keycloak realm, Logback PHI redaction, Windows WinSW service, ArchUnit & Testcontainers multi-DB suite |
| `healthcore-mobile-kmp` | Pure Kotlin + Compose Multiplatform Android (SQLCipher/WorkManager/Camera QR) + iPhone/iPadOS (Keychain/Apple Pencil/BGTaskScheduler) suite |

### Granular Composable Standalone Starters
Pick only the exact slices your facility or SaaS product requires:
- `healthcore-starter-pharmacy-inventory` (PIS only)
- `healthcore-starter-lis-diagnostics` (LIS only)
- `healthcore-starter-blood-bank` (Donor Hb screening, ISBT-128, 2-person bedside verify, hemovigilance)
- `healthcore-starter-surgery-ot` (OT scheduling, WHO Surgical Safety Checklist gate, Anesthesia & PACU Aldrete)
- `healthcore-starter-telemedicine` (WebRTC & 3G-fallback virtual care)
- `healthcore-starter-biomedical-coldchain` (2°C–8°C cold-chain MQTT webhook & Biomed PM calibration)
- `healthcore-starter-crvs-mortuary` (Births, WHO MCCD Cause-of-Death & Mortuary chain-of-custody)
- `healthcore-starter-workforce-roster` (Practitioner license expiry gate & shift rosters)
- `healthcore-starter-deidentification-research` (HIPAA Safe-Harbor 18-identifier de-identification)
- `healthcore-starter-chw-homecare` (CHW households, ANC/iCCM/MUAC referrals & Home Care RPM)
- `healthcore-starter-programs-immunization` (WHO EPI immunization & HIV ART/TB DOT/Hepatitis cascades)
- `healthcore-starter-disease-surveillance` (IDSR CIF, outbreak watch, contact tracing & DHIS2 ADX)
- `healthcore-starter-fhir-hie` (FHIR R4 IPS `$summary`, Bulk `$export`, Transaction Bundle, Subscriptions)
- `healthcore-starter-hl7-astm-dicom` (HL7v2 MLLP TCP/HTTP, ASTM E1394, DICOMweb `QIDO/STOW/WADO-RS`, CDA R2)
- `healthcore-starter-interoperability-ihe` (IHE PIXm, PDQm, MHD `DocumentReference`, mCSD `Organization`/`Location`)

---

## Build, Test & Run Locally (Requires JDK 25 + Maven 3.9+ + Docker)

> **Note:** Build and container execution require an environment with **JDK 25**, **Maven 3.9+**, **Gradle 8.10+**, and **Docker** installed.

```bash
# 1. Start PostgreSQL 16 + Keycloak 26 (or add --profile all-dbs for MSSQL 2022 & MySQL 8.4)
docker compose up -d

# 2. Run Unit & ArchUnit Governance Tests (Java 25 required)
mvn clean test

# 3. Run Testcontainers Multi-DB Liquibase Parity Suite (PostgreSQL + MSSQL + MySQL)
mvn test -Dgroups=multidb-it

# 4. Launch Quickstart Host (Port 3000)
mvn -pl healthcore-quickstart-host spring-boot:run -Dspring-boot.run.profiles=postgres,keycloak

# Optional profiles:
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
- **Clinical Research, Dynamic eConsent & OMOP CDM v5.4 Export**: Trial eligibility cohort builder & HIPAA Safe-Harbor OMOP export (`/api/v1/research/**`)
- **Resumable Chunked Encrypted Offline Media Sync**: SHA-256-verified binary attachment uploads paired with KMP `DataSyncManager` (`/api/v1/sync/media/**`)
- **Payer Clearinghouse & EDI X12 (`837`/`835`)**: Automated claim scrubbing, remittance reconciliation, FHIR `ExplanationOfBenefit`, and denial appeals (`/api/v1/clearinghouse/**`)
- **Integration DLQ & Webhook/MLLP Replay**: Operational dead-letter inspection, single/bulk replay, and circuit-breaker health (`/api/v1/ops/dlq/**`)
- **Multi-Tenant SaaS Onboarding & Feature-Flag Governance**: Self-service facility onboarding, module licensing, terminology version pinning, and per-tenant feature flags (`/api/v1/admin/tenants/**`)
- **Clinical & Operational AI Suite (`healthcore-starter-clinical-ai`)**: Ambient Scribe & Voice-to-SOAP (`/api/v1/ai/scribe/**`), Automated Medical Coding (`/api/v1/ai/coding/**`), DS4P-Aware Chart & IPS Summarizer (`/api/v1/ai/summaries/**`), Multimodal Image Triage (`/api/v1/ai/vision/**`), Advisory Clinical Decision Support (`/api/v1/ai/cds/**`), Prior-Auth & Claim Denial Appeal Writer (`/api/v1/ai/revenue/**`), Calibrated `gemini-decision-1` Triage & Policy Engine (`/api/v1/ai/decisions/**`), Multilingual Text+Voice Discharge Coach (`/api/v1/ai/coach/**`), Semantic Chart & Trial Cohort Search (`/api/v1/ai/semantic/**`), and Safe-Harbor PHI Redaction + Clinician Attestation Governance (`/api/v1/ai/governance/**`)

OpenAPI 3.1 Swagger UI is served at `http://localhost:3000/swagger-ui.html`.
