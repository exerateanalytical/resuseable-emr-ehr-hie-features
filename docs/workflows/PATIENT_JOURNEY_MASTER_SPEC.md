# OPES HealthCore — Canonical Patient Journey Master Specification

## 1. Architectural Principle: Composable Parent–Child Orchestration
HealthCore enhances `OPD_CONSULTATION_FLOW` (v2.0.0) as a **composable parent workflow** that coordinates independently executable clinical and departmental child subworkflows (`LAB_ORDER_FULFILLMENT`, `RADIOLOGY_IMAGING_FULFILLMENT`, `PHARMACY_PRESCRIPTION_FULFILLMENT`, `EMERGENCY_RESUSCITATION_ESCALATION`, `CLINICIAN_CRITICAL_RESULT_ESCALATION`).

Parallel departmental tasks (e.g., laboratory testing, chest radiography, and outpatient pharmacy dispensing) are **never** forced into a single linear state machine. Completing one order does not prematurely finalize the encounter, and late or amended results safely reopen clinician review.

## 2. Authoritative Identity & Strict 9-Identifier Separation
The MPI (`healthcore-starter-mpi`) remains the sole authoritative patient identity service within a configured identity scope. Every clinical visit opens a **new `encounterId`** against the **same persistent `patientId`**.

`ClinicalJourneyContracts.ClinicalJourneyIdentifiers` enforces strict separation of all 9 identifiers:
1. `patientId` — Persistent MPI patient identity (reused across all visits; provisional emergency identities are merged via audited MPI stewardship, never auto-merged when uncertain).
2. `encounterId` — Current clinical visit/encounter.
3. `workflowInstanceId` — Executing parent or child workflow instance.
4. `orderId` — Individual signed clinical order (lab, imaging, medication, procedure, referral).
5. `taskId` — Departmental fulfillment work task.
6. `queueEntryId` — Position and worker assignment in an operational queue.
7. `resultId` — Verified clinical observation or signed diagnostic report.
8. `invoiceId` — Financial obligation reference owned by the billing/claims module.
9. `notificationId` — Durable patient communication delivery record.

## 3. Canonical 23-Step Outpatient Patient Journey

| Step | Journey Activity | Canonical Service / Contract | Canonical Event(s) |
|---|---|---|---|
| 1 | Patient arrives at facility | `healthcore-starter-queue-management` (`RECEPTION`) | `PatientArrived` |
| 2 | Reception searches MPI before registering (or issues provisional emergency ID) | `healthcore-starter-mpi` | `PatientMatched` / `PatientRegistered` |
| 3 | Existing appointment checked in or unscheduled arrival recorded | `healthcore-starter-scheduling` | `PatientArrived` |
| 4 | One clinical encounter opened & parent `OPD_CONSULTATION_FLOW` started | `PatientJourneyOrchestratorService.startParentOutpatientJourney` | `EncounterStarted` |
| 5 | Triage/nursing task and `TRIAGE` queue entry created | `QueueManagementService.enqueue` | `QueueEntryCreated` |
| 6 | Triage nurse records acuity (ESI 1–5) and vital signs | `healthcore-starter-emergency-nursing` | `VitalSignsRecorded`, `TriageCompleted` |
| 7 | Clinically unstable patients immediately escalated (`EMERGENCY_OVERRIDE`) | `evaluateTriageAndConsultationClearance` | `ClinicalPriorityChanged`, `ServiceCleared` |
| 8 | Routine patients evaluated against configurable consultation billing clearance | `WorkflowBillingClearancePolicySpi` | `FinancialClearanceRequired` / `ServiceCleared` |
| 9 | Eligible patient enters `DOCTOR_CONSULTATION` queue | `QueueManagementService.enqueue` / `updateFinancialClearance` | `QueueEntryCreated` |
| 10 | Authorized clinician atomically claims consultation task | `QueueManagementService.claimTask` | `QueueEntryCalled`, `ConsultationStarted` |
| 11 | Clinician reviews history, allergies, active medications, prior results & triage | `healthcore-starter-emr-clinical`, `healthcore-starter-terminology-cdss` | — |
| 12 | Clinician documents examination, assessment, and clinical plan | `healthcore-starter-emr-clinical` | `ConsultationCompleted` |
| 13 | Clinician signs lab, imaging, medication, procedure, or referral orders | `healthcore-starter-orders-results` | `ClinicalOrderSigned` |
| 14 | Each signed order is replay-safely routed to its target department subworkflow | `PatientJourneyOrchestratorService.routeSignedOrder` | `LaboratoryOrderAccepted`, `ImagingOrderAccepted`, `PrescriptionRouted` |
| 15 | Service-level financial/coverage clearance evaluated per order | `WorkflowBillingClearancePolicySpi` | `FinancialClearanceRequired` / `ServiceCleared` |
| 16 | Departments execute fulfillment tasks independently via departmental queues | `QueueManagementService` + LIS / RIS / Pharmacy starters | `SpecimenCollected`, `MedicationDispensed` |
| 17 | Verified final results automatically routed to responsible clinician inbox | `deliverVerifiedResultToClinicianInbox` | `LaboratoryResultVerified`, `RadiologyReportSigned`, `ClinicianReviewRequired` |
| 18 | Critical findings trigger immediate clinical escalation & fallback SLA timer | `escalateOverdueCriticalInboxItems` | `CriticalResultDetected` |
| 19 | Privacy-minimized patient notifications dispatched per consent/release policy | `dispatchDurableNotification` + `confirmNotificationDeliveryReceipt` | `PatientNotificationRequested`, `PatientNotificationDelivered` |
| 20 | Clinician reviews results and records explicit acknowledgment & clinical action | `acknowledgeClinicianInboxItem` | `ClinicianResultAcknowledged` |
| 21 | Patient returned to consultation queue with elevated priority or booked for follow-up | `QueueManagementService.reenterAfterInvestigations` | `FollowUpRequired`, `QueueEntryCreated` |
| 22 | Treatment, admission, external referral, or outpatient discharge proceeds | `healthcore-starter-hospital-ops` / `healthcore-starter-emr-clinical` | `QueueEntryCreated` (`ADMISSIONS` / `DISCHARGE_PROCESSING`) |
| 23 | Encounter finalized strictly after `verifyCanFinalizeEncounter` confirms zero open blockers | `PatientJourneyOrchestratorService.verifyCanFinalizeEncounter` | `EncounterDischarged` |

## 4. Machine-Readable Workflow Blueprints
All 6 versioned 25-attribute JSON blueprints reside on the classpath of `healthcore-starter-workflow-engine`:
- `workflows/definitions/opd-consultation-journey.v2.json`
- `workflows/definitions/laboratory-order-fulfillment.v1.json`
- `workflows/definitions/radiology-imaging-fulfillment.v1.json`
- `workflows/definitions/pharmacy-prescription-fulfillment.v1.json`
- `workflows/definitions/emergency-resuscitation-escalation.v1.json`
- `workflows/definitions/clinician-critical-result-escalation.v1.json`
