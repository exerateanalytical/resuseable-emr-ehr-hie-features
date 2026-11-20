# OPES HealthCore — Workflow State Machines & Transactional Contracts

This document specifies the formal workflow state machines, transition guards, actor authorization checks, transactional boundaries, failure/retry semantics, audit actions, and completion criteria across all 8 Industry Capability Packs (Directive Section 8).

---

## 1. Pack 01 — Retail/Chain Pharmacy Dispensing & Controlled-Substance Workflow
- **Module**: `healthcore-starter-pharmacy-commerce` (`PharmacyCommerceService`)
- **States**: `PENDING` -> `PARTIALLY_DISPENSED` -> `DISPENSED` (or `CANCELLED`)
- **Stock Transfer States**: `IN_TRANSIT` -> `RECEIVED_CONFIRMED`
- **Purchase Order States**: `ORDERED` -> `RECEIVED`
- **Trigger Events**: Prescription intake (`POST /api/v1/pharmacy/commerce/prescriptions`), FEFO dispense (`POST /dispense`), refill (`POST /refill`), POS checkout (`POST /pos/sales`), stock transfer (`POST /transfers` -> `POST /transfers/{id}/confirm`).
- **Actor Roles & Authorization**:
  - `PERM_pharmacy:dispense` — Pharmacist dispensing & counseling
  - `PERM_inventory:manage` — Goods receipt, PO creation, stock transfers & adjustments
  - `PERM_billing:collect` — POS checkout & settlement
- **Preconditions & Validation Rules**:
  1. `FacilityContext` must be bound (`X-Facility-Id` header); missing tenant context fails with `AccessDeniedException`.
  2. FEFO allocation strictly filters out expired lots (`expiryDate < today`) and `QUARANTINED`/`RECALLED` batches.
  3. If product `controlledSubstance == true`, `secondVerifierId` is mandatory and MUST differ from `dispensedBy` (maker-checker separation enforced via `ElectronicSignatureSpi`; self-verification throws `SecurityException`).
  4. Refills cannot exceed `refillsAuthorized`; partial dispensing decrements remaining balance atomically.
- **Transactional Boundary**: Each dispense, transfer confirmation, and POS checkout runs inside a single Spring `@Transactional` boundary with JPA `@Version` optimistic locking on stock batches.
- **Failure / Retry Behavior**: Optimistic locking conflicts (`ObjectOptimisticLockingFailureException`) roll back cleanly and are safe for client retry with updated quantities; insufficient stock throws `IllegalStateException` with zero partial ledger mutation.
- **Audit Events**: `PHARMACY_PO_CREATE`, `PHARMACY_GOODS_RECEIPT`, `PHARMACY_TRANSFER_DISPATCH`, `PHARMACY_TRANSFER_CONFIRM`, `PHARMACY_STOCK_ADJUST`, `PHARMACY_RX_DISPENSE`, `PHARMACY_POS_SALE`.
- **Completion Criteria**: Batch quantity decremented, controlled-substance SHA-256 signature stored (if controlled), counseling note recorded, and `@ClinicalAudit` entry committed.

---

## 2. Pack 02 — Diagnostic Laboratory Specimen, IQC, Release, Critical Value & Amendment Workflow
- **Module**: `healthcore-starter-diagnostics-core` (`DiagnosticsCoreService`)
- **Order/Result States**: `ACCESSIONED` -> `SPECIMEN_COLLECTED` (or `REJECTED`) -> `ANALYZING` -> `TECHNICAL_VERIFIED` -> `RELEASED` -> `AMENDED`
- **Critical Value Escalation States**: `PENDING_READBACK` -> `ACKNOWLEDGED`
- **Trigger Events**: Order accession, specimen barcode collection/rejection, IQC run recording, technical result entry, pathologist/medical e-signed release, critical-value acknowledgment, result amendment.
- **Actor Roles & Authorization**:
  - `PERM_lab:specimen:manage` — Phlebotomy / accessioning / rejection
  - `PERM_lab:result:enter` — Medical technologist result entry & IQC runs
  - `PERM_lab:verify` — Pathologist / lab director release & amendment
- **Preconditions & Validation Rules**:
  1. Result release is **hard-blocked** (`IllegalStateException`) if the most recent IQC run for the analyzer/test failed Westgard control (`!passed`).
  2. Two-stage release enforces maker-checker separation (`medicalVerifierId != technologistId`) via `ElectronicSignatureSpi`.
  3. Values crossing catalog `criticalLow` / `criticalHigh` automatically create a `CriticalValueNotificationEntity` (`PENDING_READBACK`) and invoke `CriticalValueEscalationSpi`. Read-back acknowledgment requires documented `readBackNotes` and recipient clinician identity.
  4. Amendments never overwrite historical released values in place; an immutable `ResultAmendmentEntity` records prior value, amended value, reason, and SHA-256 signature.
- **Transactional Boundary**: `@Transactional` per workflow step; optimistic locking via `@Version`.
- **Audit Events**: `DIAG_CATALOG_UPSERT`, `DIAG_ORDER_ACCESSION`, `DIAG_SPECIMEN_REJECT`, `DIAG_IQC_RECORD`, `DIAG_RESULT_RELEASE`, `DIAG_CRITICAL_ACK`, `DIAG_RESULT_AMEND`.

---

## 3. Pack 03 — Pharma GMP Batch Production, QMS, Equipment Gate, CoA & Recall Workflow
- **Modules**: `healthcore-starter-pharma-manufacturing`, `healthcore-starter-pharma-qms`, `healthcore-starter-pharma-serialization`
- **Batch States**: `PLANNED` -> `LINE_CLEARED` -> `IN_PRODUCTION` -> `QUARANTINED_PENDING_QA` -> `RELEASED` (or `REJECTED` / `RECALLED`)
- **QC Certificate States**: `PENDING_REVIEW` / `OOS_HOLD` -> `PASS_COA_ISSUED`
- **Deviation / CAPA States**: `OPEN` -> `UNDER_INVESTIGATION` -> `CAPA_IN_PROGRESS` -> `CLOSED`
- **Preconditions & Validation Rules**:
  1. **Equipment Calibration Gate**: `assertEquipmentQualifiedForProduction(assetCode)` blocks production allocation if `calibrationStatus != QUALIFIED` or `calibrationDueDate < today`.
  2. **Supplier Qualification Gate**: `assertSupplierApproved(supplierCode)` blocks raw-material intake from unqualified or expired suppliers.
  3. **OOS CoA Gate**: `issueCertificateOfAnalysis` blocks issuing `PASS_COA_ISSUED` whenever `oosDetected == true` and enforces maker-checker (`qaApproverId != analystId`).
  4. **Critical CAPA Gate**: `CRITICAL` deviations/CAPAs cannot close unless `rootCause`, `capaPlan`, `effectivenessVerified == true`, and independent QA e-signature are supplied.
  5. **Complaint-to-Recall Escalation**: Escalates verified complaints to `CRITICAL` `RECALL` records with QA e-signature and links directly to `PharmaSerializationService` batch quarantine.
- **Audit Events**: `PHARMA_SOP_APPROVE`, `PHARMA_QC_RECORD`, `PHARMA_COA_APPROVE`, `PHARMA_CAPA_OPEN`, `PHARMA_COMPLAINT_ESCALATE_RECALL`, `PHARMA_CAPA_CLOSE`, `PHARMA_EQUIPMENT_REGISTER`, `PHARMA_STABILITY_RESULT`, `PHARMA_SUPPLIER_QUALIFY`.

---

## 4. Pack 04 — Life Sciences Biobank & Clinical Research (ICH E6(R3)) Workflow
- **Modules**: `healthcore-starter-biobanking`, `healthcore-starter-clinical-research`, `healthcore-starter-life-sciences-lims`, `healthcore-starter-genomics-research`
- **Trial Subject States**: `SCREENING` -> `ENROLLED` -> `COMPLETED` / `WITHDRAWN`
- **eCRF States**: `DRAFT` -> `QUERIED` -> `SDV_VERIFIED` -> `LOCKED`
- **Investigational Product (IP) States**: `DISPENSED` -> `RETURNED` / `DESTROYED`
- **Biobank Aliquot States**: `STORED` -> `THAWED_ACCESSED` -> `DEPLETED` / `DISPOSED_CONSENT_WITHDRAWN`
- **Preconditions & Validation Rules**:
  1. Subject enrollment requires `eligibilityPassed == true` AND non-blank `econsentVersion`.
  2. eCRF database lock (`signAndLockEcrf`) is **hard-blocked** whenever `openQueryCount > 0` and requires Principal Investigator e-signature.
  3. Biobank aliquot checkout is **hard-blocked** if donor consent has been revoked (`!consentActive`), and every thaw event increments `freezeThawCount`.
  4. Investigational Product destruction requires independent witness e-signature (`destructionWitnessId != reconciledBy`) and blocks over-reconciliation (`returned + destroyed > dispensed`).
  5. Serious Adverse Events (`serious == true && causality != UNRELATED`) automatically set `expeditedReportRequired = true`.
- **Audit Events**: `CLINRES_SUBJECT_ENROLL`, `CLINRES_ECRF_CAPTURE`, `CLINRES_ECRF_QUERY_OPEN`, `CLINRES_ECRF_QUERY_RESOLVE`, `CLINRES_ECRF_LOCK`, `CLINRES_SAFETY_REPORT`, `CLINRES_IP_DISPENSE`, `CLINRES_IP_RECONCILE`.

---

## 5. Packs 05–08 — Outpatient EMR, Longitudinal EHR, Hospital HIS & Regional HIE Workflows
- **EMR Encounter States**: `SCHEDULED` -> `CHECKED_IN` -> `IN_PROGRESS` -> `SIGNED_FINAL` -> `AMENDED`
- **EHR Multi-Source Reconciliation States**: `INGESTED` -> `PROVENANCE_VERIFIED` -> `RECONCILED` (or `CONFLICT_FLAGGED` for clinician review)
- **HIS Inpatient ADT States**: `PRE_ADMIT` -> `ADMITTED` -> `TRANSFERRED` -> `DISCHARGED`
- **HIE Message / DLQ Routing States**: `RECEIVED` -> `CONSENT_VERIFIED` -> `ROUTED` (or `DLQ_QUARANTINED` -> `REPLAYED`)
- **Preconditions**: Cross-facility EHR reads validate `EhrProvenanceReconciliationContract.SensitivitySegment` against active patient consent (`ConsentScope`); sensitive segments (`HIV_STATUS`, `MENTAL_HEALTH`, `SUBSTANCE_USE`, `REPRODUCTIVE_HEALTH`, `GENETIC_DATA`) are masked unless explicitly consented or audited under break-glass.
