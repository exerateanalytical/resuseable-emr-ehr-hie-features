# OPES HealthCore — Clinical Workflow Automated Test & Verification Matrix

## 1. Required Section 12 Acceptance Tests (Tests A through J)
Implemented in `healthcore-testing/src/test/java/com/opeshealth/healthcore/testing/PatientJourneyEndToEndAcceptanceTest.java`:

| Test ID | Scenario | Verified Assertions |
|---|---|---|
| **Test A** | Returning outpatient | Same authoritative MPI `patientId` reused across visits; distinct `encounterId` and `workflowInstanceId` per visit; emits `PatientRegistered` then `PatientMatched`. |
| **Test B** | Full outpatient journey | Single encounter -> signed Lab + Radiology + Pharmacy orders -> 3 parallel departmental routings & 3 queues -> billing clearance -> independent completion -> clinician inbox delivery -> explicit clinician acknowledgment -> consultation queue re-entry with elevated priority -> safe encounter finalization. |
| **Test C** | Emergency escalation | Unstable ESI 1 patient triggers `EMERGENCY_OVERRIDE` bypassing strict prepayment; consultation and STAT laboratory order activate immediately without cashier hold. |
| **Test D** | Unpaid non-emergency service | Unpaid routine lab order remains preserved intact (`PAYMENT_PENDING`, `ROUTED_AWAITING_CLEARANCE`); fulfillment and queue claiming blocked until payment confirmation; `confirmPaymentAndReleaseOrder` + `updateFinancialClearance` release task without duplicates. |
| **Test E** | Critical result escalation | Critical finding enters `CRITICAL_ESCALATION_PENDING` (`acknowledgedAt == null`); SLA expiry escalates to `fallbackClinicianId` (`ESCALATED`); financial policy resolves `CRITICAL_RESULT_COMMUNICATION` to `EMERGENCY_OVERRIDE` regardless of unpaid balance. |
| **Test F** | Multiple concurrent orders & late/amended results | Completing lab while radiology remains open blocks `verifyCanFinalizeEncounter`; completing all orders & acknowledging results permits finalization; delivering a late amended result reopens `ACTION_REQUIRED` (`acknowledgedAt == null`) and re-blocks finalization. |
| **Test G** | Duplicate event delivery | Replaying `routeSignedOrder`, `QueueManagementService.enqueue`, and `confirmPaymentAndReleaseOrder` with identical idempotency keys creates zero duplicate routings, zero duplicate queue tickets, and emits `ServiceCleared` exactly once. |
| **Test H** | Concurrent queue allocation & atomic claiming | 16 concurrent threads checking into `DOCTOR_CONSULTATION` allocate 16 strictly unique daily tickets; 8 concurrent workers racing to claim 1 ticket result in exactly 1 winner and 7 clean `IllegalStateException` rejections. |
| **Test I** | Security, tenant isolation & privacy | Cross-facility queue claiming, inbox acknowledgment, and notification receipt confirmation throw `SecurityException`; placeholder recipient addresses (`+237670000000`) and raw clinical diagnoses throw `IllegalArgumentException`; opt-out produces `SUPPRESSED_BY_POLICY`; `DELIVERED` requires explicit provider receipt. |
| **Test J** | Restart & recovery | Destroying and rebuilding fresh `QueueManagementService` + `PatientJourneyOrchestratorService` instances over durable backing stores preserves daily ticket sequence monotonicity (`LAB-0002` after `LAB-0001`), releases held orders, and confirms pending notification receipts. |
| **Schema Check** | 6 Versioned JSON Definitions | Deserializes all 6 classpath JSON files under `/workflows/definitions/*.json` into `WorkflowChecklistDefinition` and verifies required Section 9 attributes. |

## 2. Module Unit & PostgreSQL Testcontainers Integration Suites
- `healthcore-starter-queue-management/src/test/java/com/opeshealth/healthcore/queue/QueueManagementServiceTest.java`: Standalone unit & concurrency tests for atomic ticket allocation, double-claim protection, financial hold separation, department transfer, queue pause/resume, and privacy-minimized waiting-room status.
- `healthcore-quickstart-host/src/test/java/com/opeshealth/healthcore/quickstart/PatientJourneyPostgresIT.java` (`@Tag("multidb-it")`): Real PostgreSQL 16 Testcontainers integration test running `db.changelog-master.xml` (clean install + idempotent upgrade), verifying all 8 new tables (`hc_workflow_subflow_links`, `hc_order_routing_tasks`, `hc_clinician_result_inbox`, `hc_durable_notifications`, `hc_workflow_processed_events`, `hc_queue_counters`, `hc_department_queue_entries`, `hc_queue_pause_controls`), verifying database `UNIQUE` idempotency constraint enforcement, and proving durable persistence across fresh JDBC connections.

## 3. Execution Environment Honesty Note
- Static structural verification (XML well-formedness, Liquibase `<include>` resolution, JSON schema validation, Java syntax & internal API signature validation, and 8-pack Maven transitive dependency isolation) is executed directly in the workspace.
- Live JDK 25 Maven Surefire execution (`mvn test`) and Docker-backed PostgreSQL 16 Testcontainers (`mvn test -Dgroups=multidb-it`) execute in the GitHub Actions CI pipeline (`.github/workflows/pack-isolation-and-test-suites.yml`) or any developer workstation with JDK 25, Maven 3.9+, and Docker.
