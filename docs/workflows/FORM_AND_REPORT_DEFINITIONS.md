# OPES HealthCore — Structured Form Templates & Report Definitions

This specification defines the canonical form schema metadata and operational/regulatory report specifications required by Directive Section 8. All specialty templates are also represented programmatically via `com.opeshealth.healthcore.persistence.sdk.SpecialtyExtensionContract`.

---

## Part A — Structured Form & Specialty Template Definitions

Every HealthCore form template obeys a strict JSON-Schema contract with:
- `templateId` & semantic `version`
- Target `packId` / `specialtyCode`
- Field definitions with `dataType`, `required`, `ucumUnit`, `terminologySystem` (`LOINC`, `SNOMED-CT`, `ICD-11`, `ATC`, `MedDRA`), `conditionalVisibilityRule`
- Electronic signature requirement (`requiresElectronicSignature`, `makerCheckerRequired`)
- Persistence target table & column mapping
- Print / PDF template ID and localization bundle keys (`en`, `fr`)

### 1. Pediatrics Outpatient Assessment (`hc-form-pediatrics-v1`)
```json
{
  "templateId": "hc-form-pediatrics-v1",
  "version": "1.0.0",
  "packId": "healthcore-pack-emr",
  "specialty": "PEDIATRICS",
  "persistenceTarget": { "table": "hc_emr_encounters", "jsonColumn": "structured_assessment_json" },
  "printTemplate": "pdf/emr-pediatrics-visit-a4.ftl",
  "localizationKeys": ["en.forms.pediatrics", "fr.forms.pediatrics"],
  "electronicSignature": { "required": true, "role": "PHYSICIAN", "makerChecker": false },
  "fields": [
    { "key": "weightKg", "type": "number", "required": true, "ucumUnit": "kg", "loinc": "29463-7", "min": 0.3, "max": 200 },
    { "key": "heightCm", "type": "number", "required": true, "ucumUnit": "cm", "loinc": "8302-2", "min": 20, "max": 220 },
    { "key": "headCircumferenceCm", "type": "number", "required": false, "ucumUnit": "cm", "loinc": "9843-4", "conditionalRule": "patientAgeMonths <= 36" },
    { "key": "muacMm", "type": "number", "required": false, "ucumUnit": "mm", "snomed": "284473002", "conditionalRule": "patientAgeMonths >= 6 && patientAgeMonths <= 59" },
    { "key": "immunizationUpToDate", "type": "boolean", "required": true, "loinc": "11369-6" }
  ]
}
```

### 2. Diagnostic Laboratory Result Entry & Critical Alert (`hc-form-diag-result-v1`)
```json
{
  "templateId": "hc-form-diag-result-v1",
  "version": "1.0.0",
  "packId": "healthcore-pack-diagnostics",
  "persistenceTarget": { "table": "hc_diag_orders", "relatedTables": ["hc_diag_critical_notifications", "hc_diag_result_amendments"] },
  "printTemplate": "pdf/iso15189-lab-report-a4.ftl",
  "localizationKeys": ["en.forms.diagnostics", "fr.forms.diagnostics"],
  "electronicSignature": { "required": true, "role": "PATHOLOGIST_OR_LAB_DIRECTOR", "makerChecker": true },
  "fields": [
    { "key": "testCode", "type": "string", "required": true, "terminologySystem": "LOINC" },
    { "key": "numericResult", "type": "number", "required": true, "terminologySystem": "UCUM" },
    { "key": "iqcRunId", "type": "uuid", "required": true, "validationRule": "iqcRun.passed == true" },
    { "key": "criticalReadBackNotes", "type": "string", "required": false, "conditionalRule": "numericResult <= catalog.criticalLow || numericResult >= catalog.criticalHigh" }
  ]
}
```

### 3. Pharmaceutical GMP Electronic Batch Record Step (`hc-form-pharma-ebmr-v1`)
```json
{
  "templateId": "hc-form-pharma-ebmr-v1",
  "version": "1.0.0",
  "packId": "healthcore-pack-pharma-manufacturing",
  "persistenceTarget": { "table": "hc_pharma_batch_records", "relatedTables": ["hc_pqms_equipment_qual", "hc_pqms_qc_certificates"] },
  "printTemplate": "pdf/gmp-ebmr-batch-dossier-a4.ftl",
  "localizationKeys": ["en.forms.pharma", "fr.forms.pharma"],
  "electronicSignature": { "required": true, "role": "QA_QP_DIRECTOR", "makerChecker": true },
  "fields": [
    { "key": "batchNumber", "type": "string", "required": true },
    { "key": "equipmentAssetCode", "type": "string", "required": true, "validationRule": "equipment.calibrationStatus == 'QUALIFIED' && equipment.calibrationDueDate >= today" },
    { "key": "actualYieldQty", "type": "number", "required": true, "ucumUnit": "kg" },
    { "key": "lineClearanceVerified", "type": "boolean", "required": true }
  ]
}
```

### 4. Clinical Research eCRF Visit Capture (`hc-form-clinres-ecrf-v1`)
```json
{
  "templateId": "hc-form-clinres-ecrf-v1",
  "version": "1.0.0",
  "packId": "healthcore-pack-life-sciences",
  "persistenceTarget": { "table": "hc_cr_ecrf_entries" },
  "printTemplate": "pdf/ich-e6r3-ecrf-subject-casebook.ftl",
  "localizationKeys": ["en.forms.clinres", "fr.forms.clinres"],
  "electronicSignature": { "required": true, "role": "PRINCIPAL_INVESTIGATOR", "makerChecker": false },
  "fields": [
    { "key": "subjectPseudonym", "type": "string", "required": true, "validationRule": "noRawPiiPattern" },
    { "key": "sdtmDomain", "type": "enum", "values": ["DM", "VS", "LB", "AE", "EX", "CM"], "required": true },
    { "key": "openQueryCount", "type": "integer", "required": true, "validationRule": "lockAllowedOnlyWhen == 0" }
  ]
}
```

---

## Part B — Operational, Clinical & Regulatory Report Definitions

| Report ID | Pack | Metric / Purpose | Authoritative Source Tables | Mandatory Filters | Pagination & Export Formats |
|---|---|---|---|---|---|
| `RPT-PHARM-FEFO-EXPIRY` | Pack 01 | Near-expiry & expired pharmacy stock risk report | `hc_pharm_products`, `hc_pharm_batches`, `hc_pharm_stock_adjustments` | `facility_id`, `expiry_window_days` | Cursor/page (`size <= 500`); JSON, CSV, PDF |
| `RPT-PHARM-CONTROLLED-LEDGER` | Pack 01 | Narcotics & psychotropics dual-verified dispensing register | `hc_pharm_prescriptions`, `hc_pharm_products`, `hc_audit_events` | `facility_id`, `date_from`, `date_to`, `sku` | Pageable; JSON, signed PDF, CSV |
| `RPT-DIAG-TAT-CRITICAL` | Pack 02 | Laboratory turnaround time (TAT) & critical-value read-back SLA compliance | `hc_diag_orders`, `hc_diag_critical_notifications`, `hc_diag_iqc_runs` | `facility_id`, `category`, `date_from`, `date_to` | Pageable; JSON, CSV, PDF |
| `RPT-PHARMA-PQR` | Pack 03 | Annual Product Quality Review (CoA pass rate, OOS, CAPA, stability, calibration) | `hc_pqms_qc_certificates`, `hc_pqms_deviations_capa`, `hc_pqms_stability_samples`, `hc_pqms_equipment_qual` | `facility_id`, `product_code`, `review_year` | Summary JSON (`GET /api/v1/pharma/qms/pqr/{productCode}`), PDF |
| `RPT-LIFESCI-SDTM-OMOP` | Pack 04 | De-identified CDISC SDTM / CDASH-ADaM ADSL & OMOP CDM 5.4 research extract | `hc_cr_trial_subjects`, `hc_cr_ecrf_entries`, `hc_cr_safety_events` | `facility_id`, `protocol_code` | Streamed JSON, SAS-XPT/CSV transformable |
| `RPT-EHR-IPS-SUMMARY` | Pack 06 | HL7 FHIR R4 International Patient Summary (IPS) with provenance & privacy filter | `hc_emr_encounters`, `hc_hie_consents`, `hc_mpi_patients` | `facility_id`, `patient_id`, `consent_scope` | FHIR R4 JSON Bundle, PDF |
| `RPT-HIS-BED-REVENUE` | Pack 07 | Inpatient bed occupancy (BOR/ALOS) & payer/UHC revenue settlement | `hc_hosp_admissions`, `hc_uhc_invoices`, `hc_rev_claims` | `facility_id`, `ward_id`, `billing_period` | Pageable; JSON, CSV, XLSX/PDF |
| `RPT-HIE-EXCHANGE-SLA` | Pack 08 | Cross-facility message volume, consent blocks, and DLQ replay SLA | `hc_hie_routing_logs`, `hc_intops_dlq_messages`, `hc_audit_events` | `facility_id`, `partner_id`, `date_from`, `date_to` | Pageable; JSON, Prometheus metrics, CSV |
