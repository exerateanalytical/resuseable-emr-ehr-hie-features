# OPES HealthCore — Clinician Results Inbox & Durable Patient Notification Spec

## 1. Durable Clinician Results Inbox (Section 7)
Persisted in `hc_clinician_result_inbox` (`ClinicianResultInboxEntity`) with `UNIQUE(facility_id, result_id, responsible_clinician_id)` and `@Version` optimistic locking.

### All 10 Supported Clinician Inbox Statuses (`ClinicianInboxStatus`)
1. `UNREAD`
2. `AWAITING_REVIEW`
3. `VIEWED`
4. `ACKNOWLEDGED`
5. `ACTION_REQUIRED`
6. `CRITICAL_ESCALATION_PENDING`
7. `ESCALATED`
8. `DELEGATED`
9. `RESOLVED`
10. `AMENDED`

### Mandatory Safety Rules
- **Notification Delivery != Clinical Acknowledgment:** Setting `electronicNotificationSent = true` leaves `acknowledgedAt == null` and `acknowledgedBy == null`. Only an explicit call to `acknowledgeClinicianInboxItem(facilityId, inboxItemId, clinicianId, notes, markResolved)` records clinical acknowledgment and emits `ClinicianResultAcknowledged`.
- **Overdue Critical Escalation:** `escalateOverdueCriticalInboxItems(Instant now)` queries unacknowledged critical entries past `escalationDeadlineAt`, transitions them to `ESCALATED`, and reassigns responsibility to `fallbackClinicianId` (or care team escalation target).
- **Financial Independence:** Critical finding communication resolves to `EMERGENCY_OVERRIDE` in `WorkflowBillingClearancePolicySpi` and is **never** blocked by unpaid balances.
- **Encounter Readiness Grouping:** Entries share `groupedReadinessKey` (`ENC-{encounterId}`) and flag `returnToQueueRequired` to coordinate consultation queue re-entry (`QueueManagementService.reenterAfterInvestigations`).
- **Tenant Isolation:** Cross-facility access to any inbox item throws `SecurityException`.

## 2. Durable Provider-Backed Patient Notification Service (Section 8)
Persisted in `hc_durable_notifications` (`DurableNotificationEntity`) with `UNIQUE(facility_id, idempotency_key)`.

### All 6 Delivery States (`NotificationDeliveryState`)
1. `QUEUED`
2. `SUBMITTED_TO_PROVIDER`
3. `DELIVERED`
4. `FAILED`
5. `RETRYING`
6. `SUPPRESSED_BY_POLICY`

### Channels (`NotificationChannel`)
`SMS`, `WHATSAPP`, `EMAIL`, `PUSH`, `IN_APP`

### Privacy, Consent & Anti-Placeholder Rules
1. **Zero Placeholder Recipients:** Blank addresses and known placeholder addresses (`+237670000000`, `+0000000000`, `0000000000`, `placeholder@example.com`, `test@test.com`) are strictly rejected with `IllegalArgumentException`.
2. **No Premature `DELIVERED` Status:** Submitting to `NotificationProviderAdapterSpi` moves a notification only to `SUBMITTED_TO_PROVIDER` (or `RETRYING` on transient failure). Only `confirmNotificationDeliveryReceipt(facilityId, notificationId, receiptId)` marks `DELIVERED` upon authentic carrier/provider delivery receipt.
3. **Consent & Opt-Out Enforcement:** If `patientOptedOut == true` and `emergencyOverride == false`, the notification is durably logged as `SUPPRESSED_BY_POLICY` without invoking external adapters.
4. **Privacy-Minimized EN/FR Templates:** Routine notifications reject raw clinical diagnoses (`containsRawClinicalDiagnosis == true` -> `IllegalArgumentException`) and render privacy-minimized English (`en`) or French (`fr`) templates (`RESULT_READY`, `MEDICATION_READY`, `CRITICAL_FOLLOWUP`).
5. **Legacy Quickstart Preservation:** The existing in-memory `PatientMessagingService` in `healthcore-quickstart-host` remains untouched for backwards compatibility with existing quickstart demos, while all SDK consumers use `PatientJourneyOrchestratorService.dispatchDurableNotification`.
