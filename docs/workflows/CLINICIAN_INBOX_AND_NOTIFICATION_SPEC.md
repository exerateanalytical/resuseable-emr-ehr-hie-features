# OPES HealthCore — Clinician Results Inbox & Durable Patient Notification Spec

## 1. Durable Clinician Results Inbox (Section 7)
Persisted in `hc_clinician_result_inbox` (`ClinicianResultInboxEntity`) with `UNIQUE(facility_id, result_id, responsible_clinician_id)` and `@Version` optimistic locking.

### The 10 actual clinician inbox states (`ClinicianInboxStatus`)
1. `UNREAD`
2. `AWAITING_REVIEW`
3. `REVIEWED`
4. `ACKNOWLEDGED`
5. `ACTION_REQUIRED`
6. `CRITICAL_ESCALATION_PENDING`
7. `ESCALATED`
8. `RESOLVED`
9. `REASSIGNED`
10. `OVERDUE`

### Mandatory Safety Rules
- **Notification Delivery != Clinical Acknowledgment:** Setting `electronicNotificationSent = true` leaves `acknowledgedAt == null` and `acknowledgedBy == null`. Only an explicit call to `acknowledgeClinicianInboxItem(facilityId, inboxItemId, clinicianId, notes, markResolved)` records clinical acknowledgment and emits `ClinicianResultAcknowledged`.
- **Overdue Critical Escalation:** `escalateOverdueCriticalInboxItems(Instant now)` finds unacknowledged critical entries past `escalationDeadlineAt`. With a configured fallback clinician it transitions them to `ESCALATED` and assigns the fallback. Without a fallback it becomes **`OVERDUE`**, retaining the last accountable clinician and emitting an escalation event. No fallback notification is considered clinically delivered merely because it was emitted.
- **Financial Independence:** Critical finding communication resolves to `EMERGENCY_OVERRIDE` in `WorkflowBillingClearancePolicySpi` and is **never** blocked by unpaid balances.
- **Encounter Readiness Grouping:** Entries share `groupedReadinessKey` (`ENC-{encounterId}`) and flag `returnToQueueRequired` to coordinate consultation queue re-entry (`QueueManagementService.reenterAfterInvestigations`).
- **Result Identity / Replay:** A duplicate `resultId` assigned to the same clinician must retain the original facility, patient, encounter, order, and domain. A conflicting replay is rejected. An amended result clears the previous acknowledgment even when the new amendment is critical; the critical path enters `CRITICAL_ESCALATION_PENDING`, rather than silently retaining stale approval.
- **Acknowledgment Ownership:** Only the currently responsible clinician identifier can acknowledge an item; critical results require nonempty clinical review/read-back notes. An identical acknowledgment retry preserves timestamp and prevents duplicate events. A different clinician or altered terminal status requires a separate governed reassignment/review workflow.
- **Tenant Isolation:** Cross-facility read-back/acknowledgment is denied. **Remaining requirement:** Authenticated principal-to-clinician binding must be enforced by the consumer/API layer, not merely by trusting a caller-supplied `clinicianId`. Real JWT/IDOR tests are not yet green.

## 2. Durable Provider-Backed Patient Notification Service (Section 8)
Persisted in `hc_durable_notifications` (`DurableNotificationEntity`) with `UNIQUE(facility_id, idempotency_key)`.

### All 6 Delivery States (`NotificationDeliveryState`)
1. `QUEUED`
2. `SUBMITTED_TO_PROVIDER`
3. `DELIVERED`
4. `FAILED`
5. `RETRYING`
6. `SUPPRESSED_BY_POLICY`

### Integration and release limitations
- The clinician inbox and routing services are independent SDK contracts. The current shared-host demonstration calls some routing operations directly and does **not** prove that signed CPOE orders, payment receipts, LIS/RIS results, and specialty queues are automatically integrated end-to-end in each standalone application.
- Statuses are not legal/clinical certification. Actual result verification, clinician identity, fallback escalation, signed read-back evidence, audit, durable event publication, and provider/webhook authentication require institution-specific integration and review.
- The source-level regression tests in `healthcore-testing/PatientJourneyEndToEndAcceptanceTest` are not equivalent to PostgreSQL, multi-facility HTTP, or production provider-delivery tests. Full Java 25 and Testcontainers execution is pending.

### Channels (`NotificationChannel`)
`SMS`, `WHATSAPP`, `EMAIL`, `PUSH`, `IN_APP`

### Privacy, Consent & Anti-Placeholder Rules
1. **Zero Placeholder Recipients:** Blank addresses and known placeholder addresses (`+237670000000`, `+0000000000`, `0000000000`, `placeholder@example.com`, `test@test.com`) are strictly rejected with `IllegalArgumentException`.
2. **No Premature `DELIVERED` Status:** Submitting to `NotificationProviderAdapterSpi` moves a notification only to `SUBMITTED_TO_PROVIDER` (or `RETRYING` on transient failure). Only `confirmNotificationDeliveryReceipt(facilityId, notificationId, receiptId)` marks `DELIVERED` upon authentic carrier/provider delivery receipt.
3. **Consent & Opt-Out Enforcement:** If `patientOptedOut == true` and `emergencyOverride == false`, the notification is durably logged as `SUPPRESSED_BY_POLICY` without invoking external adapters.
4. **Privacy-Minimized EN/FR Templates:** Routine notifications reject raw clinical diagnoses (`containsRawClinicalDiagnosis == true` -> `IllegalArgumentException`) and render privacy-minimized English (`en`) or French (`fr`) templates (`RESULT_READY`, `MEDICATION_READY`, `CRITICAL_FOLLOWUP`).
5. **Legacy Quickstart Preservation:** The existing in-memory `PatientMessagingService` in `healthcore-quickstart-host` remains untouched for backwards compatibility with existing quickstart demos, while all SDK consumers use `PatientJourneyOrchestratorService.dispatchDurableNotification`.
