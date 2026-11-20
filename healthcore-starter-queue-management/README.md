# OPES HealthCore — Departmental Queue Management Starter (`healthcore-starter-queue-management`)

**Artifact**: `com.opeshealth.healthcore:healthcore-starter-queue-management:1.0.0`

Reusable, multi-tenant, concurrency-safe departmental queue engine for outpatient clinics, hospitals, standalone diagnostic centers, and retail/hospital pharmacies. Zero EMR, MPI, or billing dependencies are required to install this module.

## Supported Departments
`RECEPTION`, `TRIAGE`, `NURSING`, `BILLING_CASHIER`, `DOCTOR_CONSULTATION`, `LAB_COLLECTION`, `LAB_PROCESSING`, `RADIOLOGY`, `PHARMACY`, `PROCEDURES`, `ADMISSIONS`, `DISCHARGE`, plus any custom facility-configured department code.

## Queue Lifecycle States
`WAITING`, `READY`, `CALLED`, `IN_SERVICE`, `ON_HOLD`, `COMPLETED`, `SKIPPED`, `NO_SHOW`, `CANCELLED`, `TRANSFERRED`.

Financial clearance (`financialClearanceState`) is stored separately from `queueStatus`. A task may exist in `WAITING` or `ON_HOLD` while awaiting payment clearance (`eligibleForActivation = false`), and emergency overrides (`EMERGENCY_OVERRIDE`) immediately activate life-safety tasks.

## Key Engineering Guarantees
- **Concurrency-Safe Ticket Allocation**: Uses per-`(facilityId, departmentCode, serviceDate)` atomic counter rows (`hc_queue_counters`) with pessimistic locking (`PESSIMISTIC_WRITE`) and `@Version` — never unsafe `MAX(ticketNumber) + 1`.
- **Idempotent Replay Safety**: Enforces unique `(facilityId, idempotencyKey)` so replaying an order or workflow event never creates duplicate active queue entries.
- **Atomic Worker Claiming**: Optimistic `@Version` checks prevent two clinicians or technicians from claiming the same queue task simultaneously.
- **Zero Demographic Duplication**: Stores only canonical foreign identifiers (`patientId`, `encounterId`, `orderId`, `taskId`, `workflowInstanceId`).
- **Privacy-Minimized Patient Display**: Public/waiting-room status endpoints expose ticket code, department, queue status, and estimated wait time — never clinical diagnoses or sensitive identifiers.
- **Pluggable SPIs**: `QueuePrioritizationSpi` and `QueueEventSubscriptionSpi` allow developers to customize scoring and attach real-time display boards or webhooks.

## Liquibase Migration
`classpath:db/changelog/queue_management/001-queue-management.xml` (portable across PostgreSQL 16+, SQL Server 2022+, and MySQL 8.4+).
