# OPES HealthCore — Clinical Workflow Audit & Deduplication Map

## 1. Non-Destructive Consolidation Mandate
Per Section 1 of the Master Clinical Workflow & Patient Journey Directive:
- **Zero existing modules, entities, repositories, controllers, migrations, or tests were deleted or overridden.**
- Existing stronger implementations were retained intact.
- Genuine gaps (such as a standalone concurrency-safe departmental queue engine, a durable clinician results inbox, and a provider-SPI durable notification engine) were implemented additively.

## 2. Repository-Wide Module Audit Map

| Module | Existing Capabilities Audited | Connection Status | Deduplication / Consolidation Decision |
|---|---|---|---|
| `healthcore-starter-workflow-engine` | 8 state-machine blueprints (`ReusableWorkflowCatalog`, including `OPD_CONSULTATION_FLOW`), `WorkflowOrchestrationService`, `WorkflowEntities`, `WorkflowSpi`, Liquibase `001-workflow-engine.xml` | **Connected — Extended Additively** | Retained 100% of existing APIs; added `com.opeshealth.healthcore.workflow.journey.*` (`ClinicalJourneyContracts`, `PatientJourneyEntities`, `PatientJourneyOrchestratorService`), Liquibase `002-clinical-journey-contracts.xml`, and 6 machine-readable JSON workflow definitions. |
| `healthcore-starter-queue-management` | *Did not exist prior to audit* (only ad-hoc specialty worklists existed inside individual modules) | **Newly Created (60th Module)** | Created standalone, zero-EMR-coupling departmental queue starter serving all 12+ hospital departments with atomic counter allocation (`PESSIMISTIC_WRITE`), `@Version` optimistic lock, atomic claiming, and privacy-minimized status. |
| `healthcore-starter-mpi` | Authoritative MPI demographics, deterministic/probabilistic matching, duplicate review, merge/unmerge | **Connected & Authoritative** | Retained as sole patient identity owner. Queue and journey tables store only `patientId` (zero demographic duplication). |
| `healthcore-starter-scheduling` | Appointment slots, booking, check-in, no-show | **Connected** | Feeds `PatientArrived` into `OPD_CONSULTATION_FLOW` and `RECEPTION` queue. |
| `healthcore-starter-emergency-nursing` | Emergency visits, ESI triage acuity, vital signs | **Connected** | Feeds `TriageCompleted` and triggers `EMERGENCY_OVERRIDE` for unstable patients. |
| `healthcore-starter-emr-clinical` | Encounters, SOAP clinical notes, ward workflows | **Connected** | Owns `encounterId` clinical records; guarded against premature finalization by `verifyCanFinalizeEncounter`. |
| `healthcore-starter-orders-results` | Unified clinical orders & diagnostic results | **Connected** | Signed orders route replay-safely via `PatientJourneyOrchestratorService.routeSignedOrder`. |
| `healthcore-starter-lis-diagnostics` & `healthcore-starter-diagnostics-core` | ISO 15189 specimen accessioning, analyzer runs, pathologist verification | **Connected** | Executes `LAB_ORDER_FULFILLMENT` subworkflow; verified/critical results feed durable clinician inbox. |
| `healthcore-starter-radiology` | Modality worklist, DICOM study tracking, radiologist reporting | **Connected** | Executes `RADIOLOGY_IMAGING_FULFILLMENT` subworkflow; signed reports feed clinician inbox. |
| `healthcore-starter-pharmacy-lis` & `healthcore-starter-pharmacy-commerce` | In-house clinical dispensing & retail/external pharmacy commerce | **Connected** | Both supported via `OrderTargetDepartment.PHARMACY` (in-house) and `OrderTargetDepartment.EXTERNAL_PHARMACY` (external handoff). |
| `healthcore-starter-uhc-billing` & `healthcore-starter-revenue-coding-claims` | Double-entry billing ledger, tariffs, UHC/CBHI vouchers, payer claims | **Connected & Authoritative Ledger** | **Zero second ledger built.** `WorkflowBillingClearancePolicySpi` evaluates all 11 outcomes purely as a policy gate over existing billing/claims state. |
| `healthcore-starter-patient-portal` | Patient portal accounts & self-service access | **Connected** | Consumes privacy-minimized `PatientFacingQueueStatus` and policy-gated durable notifications. |
| `healthcore-starter-hospital-ops` | Bed management, admissions, transfers, discharge ops | **Connected** | Consumes `ADMISSIONS` and `DISCHARGE_PROCESSING` queue entries. |
| `healthcore-starter-terminology-cdss` | SNOMED CT / ICD-11 / LOINC / RxNorm + drug-allergy/interaction checks | **Connected** | Invoked as safety gate before order signing and pharmacy dispensing. |
| `healthcore-starter-offline-sync` | Offline queue & conflict sync | **Connected** | Aligns with replay-safe idempotency keys (`uq_hc_order_routing_idemp`, `uq_hc_queue_idempotency`, `uq_hc_wf_processed_evt`). |
| `healthcore-quickstart-host` (`PatientMessagingService`) | In-memory `ConcurrentHashMap` messaging helper using placeholder `"+237670000000"` and immediate `"DELIVERED"` status | **DUPLICATED (Legacy Demo Helper)** | **Preserved untouched** so existing quickstart host controllers/tests do not break. SDK consumers **MUST** use `PatientJourneyOrchestratorService.dispatchDurableNotification` + `NotificationProviderAdapterSpi` (durable JPA + real delivery receipts + zero placeholder recipients). |
