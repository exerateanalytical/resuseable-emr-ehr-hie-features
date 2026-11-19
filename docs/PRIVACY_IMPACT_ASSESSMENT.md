# Privacy Impact Assessment (PIA) — Opes HealthCore HIS Platform

> **Compliance Notice:** Source code alone cannot claim HIPAA, GDPR, Cameroon Law No. 2010/012, or other statutory certification. Deploying organizations remain the Data Controller / Covered Entity and must execute BAAs, DPIAs, physical safeguards, and workforce training.

---

## 1. Personal & Special-Category Data Processed
- **Identifiers:** Patient legal name, date of birth, national ID / SSN, MRN, phone, email, address, caregiver/proxy relationships.
- **Special-Category Health Data:** Clinical encounters, diagnoses (ICD-10/11), allergies, prescriptions, laboratory results, DICOM imaging metadata/studies, vital signs, emergency triage notes, insurance claims.
- **Workforce Data:** Practitioner identities, license credentials, shift assignments, and audit trails.

## 2. Privacy-by-Design Controls Implemented
1. **Tenant & Patient Authorization:**
   - Every patient-facing and clinician API requires explicit OIDC JWT scopes/authorities (`PERM_*`), never broad `isAuthenticated()` fallbacks.
   - `PatientPortalService` verifies either direct patient account ownership or an active, unexpired, scope-matched `PatientProxyGrantEntity`.
2. **AI-Assisted Processing Consent & Heuristic PHI Redaction:**
   - Patients can opt in/out of `AI-Assisted Processing` via `ConsentService`.
   - `PhiRedactionGateway` strips direct identifiers (known patient names, MRNs, SSNs, emails, phones, dates, labeled names) prior to outbound LLM calls and hashes prompts/outputs with SHA-256 so raw PHI is excluded from AI audit records.
   - **Explicit Limitation:** Regex/context redaction is a **risk-reduction control**, NOT guaranteed HIPAA Safe Harbor de-identification. Free-text indirect identifiers may survive; deploying hospitals must use a BAA-covered / enterprise zero-data-retention AI endpoint and human review.
3. **Research & Cohort Export Privacy:**
   - `HospitalAnalyticsService` enforces minimum small-cell suppression (`k >= 5` patients) on de-identified cohort exports and **four-eyes separation of duties** (requester cannot self-approve an export).
4. **Data Minimization & Encryption:**
   - Blind indexes and field-level encryption for MPI sensitive identifiers (`healthcore-starter-mpi`), validated at production startup by `ProductionSecretsStartupValidator`.

## 3. Data Subject Rights & Retention
- **Access & Portability:** Supported via Patient Portal APIs (`/api/v1/portal/**`) and FHIR R4 / IPS export endpoints (strictly tenant- and patient-scoped).
- **Correction/Amendment:** Clinical records use append-only linked amendments (`AMENDED` status) rather than destructive overwrites to satisfy medical-legal retention requirements.
- **Retention & Purge:** Database retention schedules, backup expiry, and right-to-erasure exceptions (medical-legal hold) must be configured per local jurisdiction by the hospital operator.
