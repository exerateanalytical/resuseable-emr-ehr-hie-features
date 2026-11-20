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
- `hc_order_routing_tasks` enforces `UNIQUE(facility_id, idempotency_key)` so replaying `ClinicalOrderSigned` never creates duplicate active fulfillment tasks.
- `hc_workflow_subflow_links` enforces `UNIQUE(facility_id, parent_workflow_id, child_workflow_id)` to track all parallel child subworkflows belonging to one `OPD_CONSULTATION_FLOW` parent.
- `verifyCanFinalizeEncounter(facilityId, encounterId)` blocks premature encounter closure whenever any routed order is still `ROUTED_AWAITING_CLEARANCE` or `ACTIVE_IN_DEPARTMENT`, or any critical/amended clinician inbox entry remains unacknowledged.

## 2. Automatic Result Return & Amended-Result Handling
When a laboratory result is verified (`LaboratoryResultVerified`) or a radiology report is signed (`RadiologyReportSigned`), `deliverVerifiedResultToClinicianInbox` creates or updates a durable `ClinicianResultInboxEntity`:
- Standard verified results enter `AWAITING_REVIEW`.
- Critical panic-value findings immediately enter `CRITICAL_ESCALATION_PENDING` with an escalation SLA deadline (`escalationDeadlineAt`).
- **Late / Amended Results:** Delivering an amended result (`amendedResult = true`) on a previously acknowledged `resultId` resets `acknowledgedAt = null`, clears `acknowledgedBy`, sets `inboxStatus = ACTION_REQUIRED`, and automatically blocks encounter finalization until the clinician re-acknowledges the amended report.

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
