# OPES HealthCore — Order Routing, Result Return & Canonical Domain Events

## 1. Replay-Safe Departmental Order Routing (Section 6)
When a clinician signs orders during an encounter, `PatientJourneyOrchestratorService.routeSignedOrder(RouteSignedOrderCommand, FacilityBillingPolicyConfig)` routes each order independently without collapsing parallel work into a single linear workflow:

| `OrderTargetDepartment` | Spawned Child Workflow | Target Queue Department(s) | Canonical Event Emitted |
|---|---|---|---|
| `LABORATORY` | `DIAGNOSTIC_LAB_FLOW` | `LAB_COLLECTION` -> `LAB_PROCESSING` | `LaboratoryOrderAccepted` |
| `RADIOLOGY` | Linked imaging fulfillment | `RADIOLOGY` | `ImagingOrderAccepted` |
| `PHARMACY` (In-House) | `PHARMACY_DISPENSE_FLOW` | `PHARMACY` | `PrescriptionRouted` |
| `EXTERNAL_PHARMACY` | `PHARMACY_DISPENSE_FLOW` (external handoff mode with `externalPharmacyCode`) | External pharmacy handoff (no orphan in-house dispense task) | `PrescriptionRouted` |
| `PROCEDURE` | Linked procedure task | `PROCEDURES` | `ClinicalOrderSigned` |
| `REFERRAL` | `CLOSED_LOOP_REFERRAL_FLOW` | Care coordination / HIE | `ClinicalOrderSigned` |

### Idempotency & Parent–Child Linking
- `hc_order_routing_tasks` enforces `UNIQUE(facility_id, idempotency_key)` and, after the additive `workflow/003-one-routing-per-order.xml` migration passes duplicate-data preflight, **`UNIQUE(facility_id, order_id)`**. The same signed order cannot produce multiple departmental task rows even when a replay supplies a new idempotency key. A replay whose patient, encounter, target department or external pharmacy differs from the original is rejected instead of treated as a valid retry.
- The migration intentionally **halts** if duplicate order-routing rows already exist; reconcile them with patient/financial/clinical audit review, never delete or silently merge them. Database uniqueness handles simultaneous first-write races; the caller must recover and re-query the original order after a conflict.
- `hc_workflow_subflow_links` enforces `UNIQUE(facility_id, parent_workflow_id, child_workflow_id)` to track all parallel child subworkflows belonging to one `OPD_CONSULTATION_FLOW` parent.
- `verifyCanFinalizeEncounter(facilityId, encounterId)` blocks premature encounter closure whenever any routed order is still `ROUTED_AWAITING_CLEARANCE` or `ACTIVE_IN_DEPARTMENT`, or any critical/amended clinician inbox entry remains unacknowledged.

### Automatic departmental queue composition

When `healthcore-starter-journey-queue-bridge` is installed, `routeSignedOrder` now enqueues an internal laboratory, radiology, pharmacy, procedure, or referral task **within the routing transaction**. The adapter saves `queueEntryId` on the routing record and uses the canonical patient, encounter, facility and order references for consistent identity. An unpaid routine order stays `WAITING` and cannot be claimed. `confirmPaymentAndReleaseOrder` activates the **same** queue entry after clearance, with no duplicate ticket. The routing completion gate requires the corresponding operational queue entry to be `COMPLETED`.

External pharmacies intentionally bypass the internal department queue; they require separate authenticated network dispatch and receipt processing. Without this optional bridge, the workflow engine remains a generic routing SDK and its consumer must provision queues explicitly.

This is a source-level integration with JUnit and a proposed PostgreSQL outpatient consumer test; real Spring Boot 4 / Java 25, DB rollback, external provider delivery, payment-webhook authorization, and cross-facility HTTP security are not yet verified. `routeSignedOrder` must only be called for a clinically signed order; a durable outbox/inbox subscriber is still required to automate consumption of signed CPOE events in real deployed systems.

## 2. Automatic Result Return & Amended-Result Handling
When a laboratory result is verified (`LaboratoryResultVerified`) or a radiology report is signed (`RadiologyReportSigned`), `deliverVerifiedResultToClinicianInbox` creates or updates a durable `ClinicianResultInboxEntity`:
- Standard verified results enter `AWAITING_REVIEW`.
- Critical panic-value findings immediately enter `CRITICAL_ESCALATION_PENDING` with an escalation SLA deadline (`escalationDeadlineAt`).
- **Late / Amended Results:** An amended result (`amendedResult = true`) clears `acknowledgedAt`, `acknowledgedBy` and prior review notes. **Noncritical** amendments enter `ACTION_REQUIRED`; **critical** amendments enter `CRITICAL_ESCALATION_PENDING` and receive a fresh SLA. Neither may inherit an earlier clinician acknowledgment. Conflicting patient/encounter/order identities are rejected before a task is updated.
- **Clinical acknowledgment:** Only the currently responsible clinician ID may acknowledge; critical values require documented review/read-back notes. An identical retry does not refresh the timestamp or emit another acknowledgment event. A clinician-ID string alone is not proof of authenticated human identity; consumers must bind it to the verified principal and implement signed evidence.
- **Fulfillment completion:** A task can be completed only from `ACTIVE_IN_DEPARTMENT` or `IN_PROGRESS` with a fulfillment-eligible clearance. `CANCELLED` and financial-hold states cannot be completed; a retry of an already completed task leaves the original completion timestamp unchanged.

### Verification boundaries (not complete app certification)
The SDK contract can create routing tasks and clinician inbox entries, but automatically consuming signed clinical order events and specialty result/queue events across *every standalone application* still requires actual integration wiring, provider-specific adapters and PostgreSQL/Testcontainers evidence. The orchestration acceptance suite uses synthetic/in-memory backing stores and is not proof of transactional broker delivery or clinical deployment. Emergency stabilization and critical-result communication remain independent of routine prepayment.

## 3. All 28 Canonical Durable Domain Events (Section 10)
Published through `TransactionalOutboxEntity` using `CanonicalDomainEventEnvelope` (`eventId`, `eventType`, `eventVersion`, `facilityId`, `patientId`, `encounterId`, `orderId`, `correlationId`, `causationId`, `occurredAt`, `originatingService`, `actorId`, `idempotencyKey`, `payload`):

1. `PatientMatched`
2. `PatientRegistered`
3. `EncounterStarted`
4. `PatientArrived`
5. `TriageCompleted`
6. `ClinicalPriorityChanged`
7. `VitalSignsRecorded`
8. `QueueEntryCreated`
9. `QueueEntryCalled`
10. `ConsultationStarted`
11. `ConsultationCompleted`
12. `ClinicalOrderSigned`
13. `FinancialClearanceRequired`
14. `ServiceCleared`
15. `LaboratoryOrderAccepted`
16. `ImagingOrderAccepted`
17. `PrescriptionRouted`
18. `SpecimenCollected`
19. `LaboratoryResultVerified`
20. `RadiologyReportSigned`
21. `MedicationDispensed`
22. `CriticalResultDetected`
23. `ClinicianReviewRequired`
24. `ClinicianResultAcknowledged`
25. `PatientNotificationRequested`
26. `PatientNotificationDelivered`
27. `FollowUpRequired`
28. `EncounterDischarged`
