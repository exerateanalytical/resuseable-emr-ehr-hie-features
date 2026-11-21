# Diagnostic Laboratory — independent HealthCore SDK consumer

**Status: PRE-PRODUCTION. Workflow integration test source added, but it has NOT yet passed Java 25 CI.** This is a reference consumer, not a certified LIS or complete laboratory product.

## Current scope and limitations

The example composes `healthcore-starter-diagnostics-core` and `healthcore-starter-lis-diagnostics`. The latter currently depends on the **combined legacy pharmacy/LIS starter**. The Liquibase master includes the legacy `pharmacy_lis/001-pharmacy-lis.xml` migration as a temporary compatibility requirement. Therefore this example is **not yet isolated from pharmacy dependencies**; a future LIS-only persistence extraction and independent pack test are mandatory.

The core diagnostic lifecycle has partner registration, test catalog, referral/order, rejected specimen/recollection, critical-value notification and read-back, verification/release, amendment, and quality incidents. It **does not yet prove** full specimen barcode chain-of-custody, a signed finalized report, analyzer/LIS interfacing, Westgard release enforcement throughout the independent application, human critical-result delivery evidence, or all required forms and reports.

## Safety and duplicate-delivery policy

- **QC fails closed by default.** `DiagnosticsCoreService` will not release an ordinary diagnostic order unless an explicitly configured QC policy approves it. The integration suite registers a **test-only synthetic policy**; this is not real analyzer/Westgard QC evidence and cannot be reused in production.
- **Accessions are unique within a facility.** New migration `003-diagnostics-accession-uniqueness.xml` checks for existing duplicates and halts rather than silently merging patient records. Exact replay of the same accession, patient, catalog, modality, partner and SLA reuses the existing order; conflicting payloads are rejected.
- Parallel first-time registrations still rely on the database unique constraint, so one competing request may receive a conflict that the caller must reconcile and retry safely. This is **not** distributed exactly-once processing.
- Critical-value acknowledgment stores an actor and timestamp but must be backed by independently verified delivery, clinical read-back, proper licensure and human safety review before deployment.

## Building and running the integration test

Requires Java 25, Maven 3.9+, Docker/Testcontainers, PostgreSQL 16 support, and **synthetic test data**.

```bash
# From the repository root: compile SDK modules and install locally
mvn -B -ntp -fae -DskipTests install

# Verify this consumer with Failsafe: startup + real PostgreSQL migrations + persisted workflows
mvn -B -ntp -f examples/diagnostic-laboratory/pom.xml clean verify -DskipITs=false
```

`IndependentWorkflowIT.java` boots this application's Spring context on PostgreSQL 16 Testcontainers and runs two synthetic scenarios: a persisted referral/order→critical notification→read-back→report release→amendment; and rejected specimen/QC release blocking plus cross-facility negative checks. It reads state through **fresh database connections**. The original `WorkflowSmokeTest` is only a classpath check.

**Not yet included:** authenticated HTTP/OIDC test, independent analyst QA and report-signature workflow, repeat/replay idempotency, cancellation/recovery, actual storage restart, complete generated forms and clinical regulator review. A source file existing or Maven compiling is not evidence of completion; require same-commit green Failsafe XML and no skips.

## Required complete LIS journey

1. Referring provider/clinic sends an authenticated lab order with patient match, urgency, diagnosis/context, facility and consent.
2. Generate unique accession barcode; record collection/receipt, specimen custodians and rejection/recollection.
3. Execute Westgard QC with analyzer/lot/run provenance; any failed QC must stop patient-result release.
4. Record values, reference ranges, units and critical thresholds; verify/sign result with scientist role and immutable report version.
5. Auto-route released result to responsible clinician, with signed delivery/receipt. Escalate panic values until a **verified human acknowledgment**, including timestamp and performer, is durably recorded.
6. Support amended results, safe retry, idempotent event replay and offline/network outages, all without duplicate reports or patient leaks.
7. Produce specimen labels, QC worksheet, rejection form, signed report, correction, clinician read-back form and daily turnaround/incident/critical-result reports.

**Security:** an `X-Facility-Id` header is not proof of authorization. Use verified JWT user/facility membership, enforced RBAC/ABAC, audit and actual cross-facility HTTP denial before deployment.

See [standalone acceptance matrix](../../docs/verification/EXAMPLE_WORKFLOW_ACCEPTANCE_MATRIX.md) and [20 engineering workstreams](../../docs/agents/HEALTHCORE_20_WORKSTREAMS.md).
