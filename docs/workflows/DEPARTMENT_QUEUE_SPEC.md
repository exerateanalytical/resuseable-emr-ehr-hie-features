# OPES HealthCore — Reusable Departmental Queue Specification

## 1. Module Identity & Zero-Coupling Boundary
- **Maven Artifact:** `com.opeshealth.healthcore:healthcore-starter-queue-management:1.0.0`
- **Dependencies:** `healthcore-persistence-core`, `healthcore-security-audit`, `spring-boot-starter-web` only.
- **No EMR/MPI/Billing Coupling:** Queue entries store only UUID references (`facilityId`, `patientId`, `encounterId`, `orderId`, `taskId`, `workflowInstanceId`). Patient demographic records are **never** duplicated inside queue tables.

## 2. Supported Departments
Configurable per facility; standard codes include:
`RECEPTION`, `TRIAGE`, `NURSING`, `BILLING_CASHIER`, `DOCTOR_CONSULTATION`, `LAB_COLLECTION`, `LAB_PROCESSING`, `RADIOLOGY`, `PHARMACY`, `PROCEDURES`, `ADMISSIONS`, `DISCHARGE_PROCESSING`, plus any custom facility department code.

## 3. Queue Status Lifecycle (All 10 Statuses)
`WAITING` | `READY` | `CALLED` | `IN_SERVICE` | `ON_HOLD` | `COMPLETED` | `SKIPPED` | `NO_SHOW` | `CANCELLED` | `TRANSFERRED`

### Separation of Operational Status vs. Financial Clearance
`DepartmentQueueEntryEntity` tracks `financialClearanceState` and `eligibleForActivation` independently of `queueStatus`:
- A task may exist in the queue with `financialClearanceState = PAYMENT_PENDING` (`eligibleForActivation = false`, `queueStatus = WAITING`).
- Attempting to `claimTask` or `callEntry` while `eligibleForActivation == false` throws `IllegalStateException`.
- When `updateFinancialClearance(facilityId, queueEntryId, "PAID" | "INSURANCE_AUTHORIZED" | "EMERGENCY_OVERRIDE" | ...)` is invoked, the entry transitions to `eligibleForActivation = true` and `queueStatus = READY` without duplicating the ticket.

## 4. Concurrency & Replay-Safety Guarantees
1. **Atomic Ticket Allocation:** Uses `hc_queue_counters` with `UNIQUE(facility_id, department_code, service_date)` locked via `@Lock(LockModeType.PESSIMISTIC_WRITE)` (`findForUpdate`) plus an in-process per-key `ReentrantLock`. Unsafe `MAX(ticketNumber)+1` queries are strictly prohibited.
2. **Atomic Task Claiming:** Synchronized per `queueEntryId` + JPA `@Version` optimistic lock (`version` column). Concurrent workers racing to claim the same ticket result in exactly **1** success (`IN_SERVICE`) and clean `IllegalStateException` rejections for all other callers.
3. **Replay Idempotency:** `UNIQUE(facility_id, idempotency_key)` on `hc_department_queue_entries` guarantees replaying an order or enqueue event returns the existing active queue entry without creating duplicate work tasks.

## 5. Extension SPIs & REST Gateway
- `QueuePrioritizationSpi`: Override default urgency scoring (`STAT=100`, `URGENT=75`, `ROUTINE=40`, `EMERGENCY_OVERRIDE>=110`, `+10/re-entry`).
- `QueueEventSubscriptionSpi`: Subscribe to real-time queue events (`QueueEntryCreated`, `QueueEntryCalled`, `QueueEntryClaimed`, `QueueEntryCompleted`, `QueueSlaBreached`, `ServiceCleared`) for waiting-room displays or SSE/WebSockets.
- **REST Endpoints (`/api/v1/queues`):**
  - `POST /api/v1/queues/entries` — Atomic idempotent enqueue
  - `GET /api/v1/queues/departments/{departmentCode}/worklist` — Priority & check-in ordered worklist
  - `POST /api/v1/queues/entries/{queueEntryId}/claim?workerId={id}` — Atomic worker claim
  - `POST /api/v1/queues/entries/{queueEntryId}/call?staffId={id}` — Call ticket to counter/room
  - `POST /api/v1/queues/entries/{queueEntryId}/complete` — Complete queue service
  - `GET /api/v1/queues/entries/{queueEntryId}/patient-status` — Privacy-minimized waiting-room status (`ticketNumber`, `departmentCode`, `status`, `positionAhead`, `estimatedWaitMinutes`, `queuePaused` — zero PHI/diagnosis).

## 6. Liquibase Schema
- Changelog: `classpath:db/changelog/queue_management/001-queue-management.xml`
- Tables: `hc_queue_counters`, `hc_department_queue_entries`, `hc_queue_pause_controls` (multi-dialect PostgreSQL 16+, MSSQL 2022+, MySQL 8.4+).
