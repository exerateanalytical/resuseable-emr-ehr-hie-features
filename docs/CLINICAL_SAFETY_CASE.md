# Clinical Safety Case — Opes HealthCore HIS Platform

> **Regulatory Notice:** This software is an engineering platform and reference implementation. It is **not** a cleared medical device (FDA/CE/MDR/ISO 13485) and makes no claim of clinical certification. Each deploying healthcare organization must perform local clinical safety governance (e.g., DCB0129/DCB0160 or equivalent) prior to live patient care.

---

## 1. Clinical Safety Principles
1. **No Autonomous Diagnosis or Prescribing:** Every AI-generated artifact is quarantined in `DRAFT_PENDING_ATTESTATION` and requires explicit licensed clinician review and sign-off.
2. **Fail-Closed Safety Gates:** Expired/missing practitioner credentials, missing critical read-back notes, uncompleted MPPS imaging steps, and insufficient pharmacy stock fail closed rather than silently succeeding.
3. **Closed-Loop Critical Communications:** Both Laboratory critical results (`healthcore-starter-orders-results`) and Radiology critical findings (`healthcore-starter-radiology`) require explicit clinician acknowledgment and documented verbal read-back notes.
4. **Immutable Clinical Provenance:** Signed radiology reports and verified lab results are never overwritten in place; corrections create linked `AMENDED` records preserving full clinical history.

## 2. Clinical Hazard Log & Implemented Controls

| Hazard ID | Clinical Hazard | Potential Harm | Software Control | Verification Status |
| :--- | :--- | :--- | :--- | :--- |
| **HAZ-01** | Unlicensed or suspended practitioner signs orders/notes | Inappropriate clinical treatment | Fail-closed credential check (`CLIN-001`); `allow-unregistered-in-dev` rejected in `prod` by `ProductionSecretsStartupValidator`. | Unit-tested; requires live hospital credential feed integration. |
| **HAZ-02** | Medication prescribed despite severe allergy or renal impairment | Anaphylaxis or drug toxicity | Order safety context loads patient allergies, active meds, renal/weight data or blocks order (`CLIN-002`). | Unit-tested; requires clinical pharmacologist review of rule base. |
| **HAZ-03** | Wrong-patient or wrong-medication bedside administration | Severe medication error | 5-Rights wristband + medication barcode verification in eMAR (`CLIN-003`). | Unit-tested; requires physical barcode scanner validation on wards. |
| **HAZ-04** | Overselling or dispensing expired/quarantined pharmacy lots | Under-treatment or expired drug administration | Concurrency-safe FEFO stock decrement excluding expired/quarantined lots (`PHARM-001`). | Unit-tested; requires concurrent load testing on target DB. |
| **HAZ-05** | Missed critical lab or radiology finding | Delayed life-saving intervention | Unacknowledged critical worklists + mandatory read-back acknowledgment in `orders-results` and `radiology` starters. | Unit-tested; requires local escalation pager/SMS SLA configuration. |
| **HAZ-06** | Unverified preliminary lab result released directly to patient | Patient anxiety or inappropriate self-action | `PatientPortalService` strictly filters to `FINAL` and `AMENDED` results; hides `PRELIMINARY`. | Unit-tested. |
| **HAZ-07** | Patient deterioration missed on inpatient ward | Preventable cardiac arrest / sepsis mortality | Deterministic NEWS2 vital-sign scoring with automatic `HIGH`/`MEDIUM`/`LOW_MEDIUM_RED` escalation flags (`EmergencyNursingService`). | Unit-tested; requires local clinical sign-off on NEWS2 thresholds (e.g., hypercapnic COPD Scale 2). |
| **HAZ-08** | Premature inpatient discharge without medication reconciliation | Readmission or post-discharge adverse drug event | 5-point multi-disciplinary `DischargeReadinessEntity` gate (`medReconciliation`, `dischargeSummary`, `patientEducation`, `followUp`, `billingCleared`). | Unit-tested. |

## 3. Clinical Sign-Off Required Before Production Go-Live
Independent clinical review is mandatory for: Triage (ESI), Vital signs (NEWS2), Order safety rules, Medication reconciliation, Blood compatibility, Lab Westgard QC & panic values, Clinical AI workflows, Predictive risk scoring, Clinical coding, and De-identification.
