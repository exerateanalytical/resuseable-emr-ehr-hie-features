# Opes HealthCore SDK — EMR, EHR & HIE Developer Guide

**Author / Principal Architect:** Jude Nshome — **Opes Health Systems**  
**Email:** `nshome@opesware.com`  
**Website:** [https://www.opeshealthsystems.com](https://www.opeshealthsystems.com)  
**Location:** Douala, Cameroon  

---

## 1. Purpose of This Guide

**Opes HealthCore SDK** is a modular, 100% pure **Java 25 + Spring Boot 4.0** (plus **Kotlin Multiplatform + SQLDelight** for Android/iPhone/iPad) digital health platform. Instead of building clinical safety, HL7 FHIR R4 interoperability, HIPAA/GDPR audit chains, multi-tenant RLS, and offline synchronization from scratch, engineering teams compose only the Spring Boot starters their healthcare product needs.

This handbook provides copy-pasteable Maven recipes, architecture blueprints, security wiring, and end-to-end API workflows for assembling:
1. **Clinic / Hospital EMR**
2. **Full Enterprise Hospital EHR** (EMR + PIS + LIS + Radiology MWL + Clearinghouse Billing)
3. **Regional / National HIE Gateway** (MPI + Bulk `$export` + IPS `$summary` + SMART Health Links + UDAP/XCPD/XCA + DS4P Consent + eCQM)
4. **Standalone Pharmacy (PIS) or Laboratory (LIS) SaaS**
5. **Offline-First Mobile & CHW Apps** (Android / iOS / iPadOS via KMP `DataSyncManager`)

---

## 2. Quick-Start Maven BOM Recipes by Application Archetype

All projects import the centralized `healthcore-bom` first so Spring Boot 4.0, HAPI FHIR R4, Liquibase, Resilience4j, and JDBC driver versions stay aligned.

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.opeshealth.healthcore</groupId>
            <artifactId>healthcore-bom</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### Recipe A: Clinic / Hospital EMR App
```xml
<dependencies>
    <dependency><groupId>com.opeshealth.healthcore</groupId><artifactId>healthcore-persistence-core</artifactId></dependency>
    <dependency><groupId>com.opeshealth.healthcore</groupId><artifactId>healthcore-security-audit</artifactId></dependency>
    <dependency><groupId>com.opeshealth.healthcore</groupId><artifactId>healthcore-starter-mpi</artifactId></dependency>
    <dependency><groupId>com.opeshealth.healthcore</groupId><artifactId>healthcore-starter-emr-clinical</artifactId></dependency>
    <dependency><groupId>com.opeshealth.healthcore</groupId><artifactId>healthcore-starter-terminology-cdss</artifactId></dependency>
    <dependency><groupId>com.opeshealth.healthcore</groupId><artifactId>healthcore-starter-workflow-engine</artifactId></dependency>
</dependencies>
```

### Recipe B: Full Enterprise EHR (EMR + Pharmacy + Lab + Radiology + Billing + Dashboards)
Include all modules from **Recipe A**, plus:
```xml
<dependencies>
    <dependency><groupId>com.opeshealth.healthcore</groupId><artifactId>healthcore-starter-pharmacy-inventory</artifactId></dependency>
    <dependency><groupId>com.opeshealth.healthcore</groupId><artifactId>healthcore-starter-lis-diagnostics</artifactId></dependency>
    <dependency><groupId>com.opeshealth.healthcore</groupId><artifactId>healthcore-starter-uhc-billing</artifactId></dependency>
    <dependency><groupId>com.opeshealth.healthcore</groupId><artifactId>healthcore-starter-surgery-ot</artifactId></dependency>
    <dependency><groupId>com.opeshealth.healthcore</groupId><artifactId>healthcore-starter-blood-bank</artifactId></dependency>
    <dependency><groupId>com.opeshealth.healthcore</groupId><artifactId>healthcore-starter-biomedical-coldchain</artifactId></dependency>
    <dependency><groupId>com.opeshealth.healthcore</groupId><artifactId>healthcore-starter-templates-dashboards</artifactId></dependency>
</dependencies>
```

### Recipe C: Regional / National HIE Gateway
```xml
<dependencies>
    <dependency><groupId>com.opeshealth.healthcore</groupId><artifactId>healthcore-persistence-core</artifactId></dependency>
    <dependency><groupId>com.opeshealth.healthcore</groupId><artifactId>healthcore-security-audit</artifactId></dependency>
    <dependency><groupId>com.opeshealth.healthcore</groupId><artifactId>healthcore-starter-mpi</artifactId></dependency>
    <dependency><groupId>com.opeshealth.healthcore</groupId><artifactId>healthcore-starter-fhir-hie</artifactId></dependency>
    <dependency><groupId>com.opeshealth.healthcore</groupId><artifactId>healthcore-starter-interoperability-ihe</artifactId></dependency>
    <dependency><groupId>com.opeshealth.healthcore</groupId><artifactId>healthcore-starter-hl7-astm-dicom</artifactId></dependency>
    <dependency><groupId>com.opeshealth.healthcore</groupId><artifactId>healthcore-starter-api-product</artifactId></dependency>
</dependencies>
```

---

## 3. Multi-RDBMS Configuration & Running Locally

Opes HealthCore natively supports **PostgreSQL 16+**, **MySQL 8.4+**, and **SQL Server 2022+** with **Liquibase** as the sole canonical schema migration owner.

```bash
# Start PostgreSQL 16 + Keycloak 26
docker compose up -d

# Run on PostgreSQL 16 with dev-seed demonstration hospital + Reference Hospital UI (Port 3000)
mvn -pl healthcore-quickstart-host spring-boot:run -Dspring-boot.run.profiles=postgres,keycloak,dev-seed

# Run on MySQL 8.4
mvn -pl healthcore-quickstart-host spring-boot:run -Dspring-boot.run.profiles=mysql,keycloak

# Run on Microsoft SQL Server 2022
mvn -pl healthcore-quickstart-host spring-boot:run -Dspring-boot.run.profiles=mssql,keycloak
```

Interactive OpenAPI 3.1 Swagger UI is served at:
`http://localhost:3000/swagger-ui.html`
Pure Java Reference Hospital Workstation UI is served at:
`http://localhost:3000/ui/overview`

---

## 4. Security, Multi-Tenancy & HIPAA/GDPR Compliance Architecture

1. **Tenant Isolation (`X-Facility-Id` + PostgreSQL RLS)**:
   - Every request carries `X-Facility-Id: <facility-uuid>`.
   - `FacilityContext` binds the tenant UUID to both Hibernate's `facilityFilter` and PostgreSQL session RLS (`app.current_facility_id`).
2. **SMART-on-FHIR v2 / Keycloak OIDC RBAC**:
   - Discovery metadata is exposed at `GET /.well-known/smart-configuration`.
   - 18 fine-grained clinical roles (`ROLE_PHYSICIAN`, `ROLE_NURSE`, `ROLE_PHARMACIST`, `ROLE_LAB_TECH`, `ROLE_RADIOLOGIST`, `ROLE_COMPLIANCE_OFFICER`, `ROLE_HIE_ADMIN`, etc.).
3. **Tamper-Evident SHA-256 Audit Chain**:
   - `PatientAuditAspect` intercepts every FHIR REST, GraphQL, CDS Hooks, Bulk Export, Consent, IPS, and HIE endpoint, recording SHA-256 hash-chained `AuditEvent` entries in `hc_audit_logs`.
   - Verify chain integrity at any time via `GET /api/v1/audit-events/verify-chain`.
   - Export HIPAA § 164.528 / GDPR Article 15 disclosure reports via `GET /api/v1/audit-events/patient/{patientId}/compliance-report`.
4. **DS4P Data Segmentation for Privacy & Break-the-Glass (`ETREAT`)**:
   - Register patient privacy directives via `POST /api/v1/fhir/Consent` (`HIV`, `PSY`, `ETH`, `SDV`, `GEN`, `VIP`).
   - Emergency overrides via `POST /api/v1/fhir/Consent/$break-the-glass` require an authenticated `clinicianId` and clinical justification (`>= 10 chars`), generate a mandatory `BreakTheGlassAuditFlag` (`PENDING_COMPLIANCE_REVIEW`), and alert Privacy Officers.

---

## 5. End-to-End API Playbooks

### Playbook 1: EMR Patient Onboarding, MPI Matching & Clinical CarePlan
```bash
# 1. Onboard a multi-tenant hospital facility
curl -X POST http://localhost:3000/api/v1/admin/tenants/onboard \
  -H "Content-Type: application/json" \
  -d '{"facilityName":"Douala Central Hospital","countryIsoCode":"CM","city":"Douala","archetype":"HOSPITAL_EHR"}'

# 2. Check for duplicate patient records via Probabilistic MPI ($match)
curl -X POST http://localhost:3000/api/v1/fhir/Patient/\$match \
  -H "Content-Type: application/fhir+json" \
  -d '{"resourceType":"Parameters","parameter":[{"name":"resource","resource":{"resourceType":"Patient","name":[{"family":"Mbarga","given":["Clarisse"]}],"birthDate":"1988-03-19","gender":"female"}}]}'

# 3. Enroll patient into Diabetes Type 2 Longitudinal CarePlan & check overdue care gaps
curl -X POST http://localhost:3000/api/v1/care-plans/enroll \
  -H "Content-Type: application/json" \
  -d '{"patientId":"<PATIENT_UUID>","pathwayCode":"DIABETES_T2","leadClinicianId":"dr.nshome"}'
```

### Playbook 2: Radiology MWL, Closed-Loop Critical Findings & Patient Messaging
```bash
# 1. Schedule STAT Chest CT into DICOM Modality Worklist (MWL)
curl -X POST http://localhost:3000/api/v1/radiology/mwl/orders \
  -H "Content-Type: application/json" \
  -d '{"patientId":"<PATIENT_UUID>","orderingClinicianId":"dr.emergency","modality":"CT","procedureCode":"71260","procedureDescription":"CT Chest with Contrast","bodySite":"CHEST","statUrgent":true}'

# 2. Sign Radiology DiagnosticReport with Critical Finding Escalation
curl -X POST http://localhost:3000/api/v1/radiology/reports \
  -H "Content-Type: application/json" \
  -d '{"orderId":"<ORDER_UUID>","radiologistId":"dr.rad","findingsText":"Bilateral pulmonary emboli","impressionText":"Acute massive pulmonary embolism","criticalFindingDetected":true,"criticalFindingCategory":"PULMONARY_EMBOLISM"}'

# 3. Send bilingual WhatsApp/SMS patient recall notification
curl -X POST http://localhost:3000/api/v1/messaging/send \
  -H "Content-Type: application/json" \
  -d '{"patientId":"<PATIENT_UUID>","channel":"SMS","templateCode":"APPOINTMENT_REMINDER","language":"fr","recipientAddress":"+237670112233","templateVariables":{"date":"2026-11-22 09:00","facility":"Douala Central Hospital"}}'
```

### Playbook 3: National HIE — Signed IPS `$summary`, SMART Health Links (`shlink:/`), `$everything`, UDAP/XCA & eCQM
```bash
# 1. Generate Ed25519 JWS-signed International Patient Summary ($summary) with QR code
curl "http://localhost:3000/api/v1/fhir/Patient/<PATIENT_UUID>/\$summary?alg=Ed25519&deflate=true"

# 2. Wrap the IPS Bundle in a Passcode-Protected AES-256-GCM SMART Health Link (shlink:/)
curl -X POST http://localhost:3000/api/v1/shl \
  -H "Content-Type: application/json" \
  -d '{"patientId":"<PATIENT_UUID>","label":"Travel Emergency IPS","passcode":"482910","longTermLiveUpdating":true,"expirationHours":168,"initialFiles":[{"contentType":"application/fhir+json","fileName":"ips.json","plainContent":"{\"resourceType\":\"Bundle\"}"}]}'

# 3. Export Full Patient Chart Portability Bundle ($everything)
curl "http://localhost:3000/api/v1/fhir/Patient/<PATIENT_UUID>/\$everything"

# 4. Run Cross-Community Patient Discovery (IHE XCPD) & Document Query (IHE XCA)
curl -X POST http://localhost:3000/api/v1/hie/xcpd/discover \
  -H "Content-Type: application/json" \
  -d '{"familyName":"Mbarga","givenName":"Clarisse","birthDate":"1988-03-19","purposeOfUse":"TREAT"}'

# 5. Evaluate Clinical Quality Measure (CMS122 HbA1c Control) via $evaluate-measure
curl -X POST http://localhost:3000/api/v1/fhir/Measure/CMS122-HBA1C-CONTROL/\$evaluate-measure \
  -H "Content-Type: application/json" \
  -d '{"patientCohort":[{"patientId":"<PATIENT_UUID>","meetsInitialPopulation":true,"meetsDenominatorExclusion":false,"meetsDenominatorException":false,"observedNumericValue":6.4,"stratumKey":"OUTPATIENT"}]}'
```

### Playbook 4: Revenue Cycle (EDI X12 837/835), Research OMOP CDM & Operations DLQ Replay
```bash
# 1. Scrub & Submit EDI X12 837P Claim
curl -X POST http://localhost:3000/api/v1/clearinghouse/claims \
  -H "Content-Type: application/json" \
  -d '{"patientId":"<PATIENT_UUID>","payerId":"CNPS-UHC","memberPolicyNumber":"POL-99281","claimType":"837P","renderingProviderNpi":"CM-MD-4481","icd10DiagnosisCodes":["E11.9"],"serviceLines":[{"cptOrProcedureCode":"99214","description":"Outpatient Follow-Up","units":1,"unitChargeMinorUnits":15000}],"currencyCode":"XAF"}'

# 2. Export HIPAA Safe-Harbor OMOP CDM v5.4 Research Dataset (for eConsented patients)
curl -X POST http://localhost:3000/api/v1/research/studies/OPES-CARDIO-2026/omop-export \
  -H "Content-Type: application/json" \
  -d '[{"patientId":"<PATIENT_UUID>","ageYears":42,"gender":"female","activeIcd10Codes":["I10","E11.9"]}]'

# 3. Inspect and Bulk-Replay Failed Integration Webhooks / MLLP Events in DLQ
curl http://localhost:3000/api/v1/ops/dlq/summary
curl -X POST "http://localhost:3000/api/v1/ops/dlq/replay-bulk?operatorId=ops.lead"
```

---

## 6. Building Offline-First Mobile / Tablet Apps (KMP + SQLDelight `DataSyncManager`)

Add the `healthcore-mobile-kmp:healthcore-mobile-sdk` dependency to your Android or iOS/iPadOS host app:

1. **Initialize SQLDelight & `DataSyncManager`**:
   ```kotlin
   val syncManager = DataSyncManager(
       driverFactory = PlatformSqlDriverFactory(context),
       baseUrl = "https://api.yourhospital.org",
       httpClient = httpClient,
       nodeId = "android-ward-tablet-04"
   )
   ```
2. **Generate & Queue Offline FHIR Resources** (auto-coalesces repeated edits and assigns offline UUIDv7 + HLC timestamps):
   ```kotlin
   syncManager.enqueueOfflineFhirResource(
       facilityId = facilityId,
       resourceType = "Observation",
       fhirResourceJson = vitalObservationJson,
       operation = "CREATE",
       wallClockMs = currentTimeMillis()
   )
   ```
3. **Background Sync & Conflict Resolution**:
   - Android schedules `WorkManagerSyncWorker.schedulePeriodicSync(...)`.
   - iOS/iPadOS schedules `BgTaskSyncScheduler.registerDataSyncManager(...)`.
   - High-risk clinical safety collisions (`AllergyIntolerance`, `MedicationRequest`, `ClinicalNote`, `Consent`) are quarantined in `local_fhir_sync_conflict` for clinician resolution via `syncManager.resolveQuarantinedConflict(...)`.
   - Demographic/Encounter edits (`Patient`, `Encounter`) automatically perform 3-way JSON field merging (`THREE_WAY_FIELD_MERGE`).
   - Encrypted offline media attachments (wound photos, PDFs) sync via chunked resumable uploads to `/api/v1/sync/media/**`.

---

## 7. Production Deployment & Go-Live Checklist

- [ ] **Database & RLS**: Run Liquibase migrations (`db.changelog-master.xml`) and apply PostgreSQL RLS policies (`enable-rls-policies.sql`) before opening clinical traffic.
- [ ] **Identity & mTLS**: Point `spring.security.oauth2.resourceserver.jwt.issuer-uri` to production Keycloak/OIDC and enable `mtls` profile for HIE gateway traffic.
- [ ] **Signing Keys**: Rotate RS256 / Ed25519 signing keys into HSM/KMS and publish `/.well-known/jwks.json` for IPS and Digital Health Certificate verification.
- [ ] **Rate Limiting & DLQ Monitoring**: Configure Bucket4j quotas per tenant archetype (`TenantGovernanceController`) and monitor `/api/v1/ops/dlq/summary` alerts.
- [ ] **HIPAA/GDPR Audit Verification**: Schedule automated daily verification of `/api/v1/audit-events/verify-chain` and review pending Break-the-Glass flags at `/api/v1/consent/break-the-glass/audit-flags`.
- [ ] **Clinical AI Governance**: Set `GEMINI_API_KEY` exclusively in server-side secrets, confirm `HEALTHCORE_AI_ENFORCE_SAFE_HARBOR_REDACTION=true`, and train clinicians to attest drafts via `/api/v1/ai/governance/drafts/{draftId}/attest`.

---

## 8. Clinical & Operational AI Suite (`healthcore-starter-clinical-ai`)

Add the composable Clinical AI starter to any Spring Boot 4.0 EMR/EHR/HIE application:

```xml
<dependency>
    <groupId>com.opeshealth.healthcore</groupId>
    <artifactId>healthcore-starter-clinical-ai</artifactId>
</dependency>
```

### Server-Side Model Routing & Guardrails
- **`gemini-4-hydrogen`**: Ambient SOAP + FHIR R4 drafting (`/api/v1/ai/scribe/soap-draft`), ICD-10/ICD-11/SNOMED/LOINC/CPT coding (`/api/v1/ai/coding/suggest`), DS4P-aware SBAR/ED/IPS summaries (`/api/v1/ai/summaries/generate`), multimodal image triage (`/api/v1/ai/vision/triage`), advisory CDS (`/api/v1/ai/cds/evaluate`), prior-auth/appeal letters (`/api/v1/ai/revenue/narratives`), and bilingual discharge coaching (`/api/v1/ai/coach/discharge-plan`).
- **`gemini-3.5-transcribe`**: Pre-recorded consultation audio transcription inside the Ambient Clinical Scribe workflow.
- **`gemini-3.8-flash-lite-tts`**: Patient-friendly WAV voice discharge instructions (`audio/wav`, 24kHz mono 16-bit).
- **`gemini-embedding-2`**: Safe-Harbor redacted semantic chart search & clinical trial cohort matching (`/api/v1/ai/semantic/index`, `/api/v1/ai/semantic/search`).
- **`gemini-decision-1`** (`POST /v1beta/decisions`): Calibrated ESI 1–5 emergency triage, sepsis escalation probability gates, and prior-auth medical necessity rubric scoring (`/api/v1/ai/decisions/emergency-triage`, `/api/v1/ai/decisions/prior-auth-rubric`).

### Mandatory Human-in-the-Loop Attestation Flow
1. Preview HIPAA Safe-Harbor redaction before sending any prompt:
   ```bash
   curl -X POST http://localhost:3000/api/v1/ai/governance/redact-preview \
     -H "Content-Type: application/json" \
     -d '{"rawClinicalText":"Patient: Jean Mbarga, MRN: CM-99281, DOB: 1982-04-19, phone +237-699-123-456 presents with fever 39.2C."}'
   ```
2. Generate any clinical AI draft (returns `draftId` with status `DRAFT_PENDING_ATTESTATION`).
3. Clinician reviews and signs off before chart commit:
   ```bash
   curl -X POST http://localhost:3000/api/v1/ai/governance/drafts/{draftId}/attest \
     -H "Content-Type: application/json" \
     -d '{"practitionerId":"DR-NGONO-402","decision":"APPROVED","attestationReason":"Verified against bedside vitals and exam"}'
   ```

