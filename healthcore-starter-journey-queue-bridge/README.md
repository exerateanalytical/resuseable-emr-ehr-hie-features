# HealthCore — clinical journey → departmental queue bridge

**Status: pre-production; end-to-end Java 25, PostgreSQL and security tests pending.**

This is an optional Spring Boot auto-configuration module for outpatient, hospital and specialty-clinic compositions. It connects the reusable `healthcore-starter-workflow-engine` and `healthcore-starter-queue-management` without creating a dependency cycle between them.

A signed laboratory, imaging, in-house pharmacy, procedure or referral order provisionally creates exactly one departmental queue entry using a deterministic `ORDER-{orderId}-{targetDepartment}` idempotency key. The queue uses the authoritative facility/patient/encounter/order IDs from the routing record. Patients who still need to pay enter a waiting queue; a confirmed clearance activates that *same* ticket. ESI emergencies receive STAT priority and the designated emergency clearance policy.

The orchestration layer stores `queueEntryId` on `DepartmentOrderRoutingEntity` and refuses `markRoutedOrderCompleted` until the corresponding queue entry is actually `COMPLETED`. The adapter validates the queue record’s patient, visit, order, facility and department before updating clearance or marking completion. External pharmacy handoffs are deliberately not queued as in-house pharmacy work and still need an authenticated external routing adapter.

## Deployment boundaries

- This module is auto-imported through Spring Boot `AutoConfiguration.imports`; inclusion via the EMR capability pack enables it where compatible configuration exists.
- Queue-entry creation and routing are expected to participate in the caller's database transaction; application-level retry handling and rollback should be verified on PostgreSQL 16.
- This does **not** automatically consume arbitrary signed CPOE events. Consumers must invoke `routeSignedOrder` after a clinically authorized signature, or add an outbox/inbox event adapter. Calling it directly from the wrong security context must be blocked in the API/service.
- The module does not authenticate payment receipts, verify lab QC or create actual LIS/RIS results. Critical-result notifications are a separate safety pathway and never prove human acknowledgment merely because a message was submitted.
- A complete workflow needs generated forms, signed reports, patient-specific security, replay/concurrent-write tests, provider integrations and clinician acceptance.
