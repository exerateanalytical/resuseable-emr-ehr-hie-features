# Standalone Diagnostic Laboratory SDK consumer

**State: pre-production / full workflow NOT VERIFIED.** This Java 25 + Spring Boot 4 reference consumer is intended to compose laboratory-specific HealthCore modules. It is **not** evidence of ISO 15189 certification or a complete deployed LIS.

## Architecture and known gaps

- **Declared modules:** `healthcore-starter-diagnostics-core`, `healthcore-starter-lis-diagnostics` plus web/actuator. The LIS starter currently also depends on the combined `healthcore-starter-pharmacy-lis` module. Therefore the previous claim of strict pharmacy-module independence **has not been proven** by an effective Maven dependency tree and must not be repeated as fact.
- **Database:** PostgreSQL + Liquibase. The example currently declares diagnostics-core migrations, while the legacy LIS critical-result service uses `hc_diagnostic_reports` from the combined pharmacy/LIS schema. Reconcile the migration and service dependencies **before** claiming a complete runnable standalone LIS.
- **Authorization:** every report-access/read-back action requires verified OIDC/JWT authority and an active authorized facility context; `X-Facility-Id` supplied by an untrusted client is *not* authentication. Validate cross-tenant denial using an actual HTTP principal.
- **Critical read-back:** the legacy path can store a clinician identifier on a verified critical report. It does not yet persist a signed, timestamped read-back by the laboratory scientist or prove human delivery; this must be implemented and clinically reviewed before representing it as a fully closed-loop alert.

## Local development prerequisites

JDK 25, Maven 3.9+, a configured test OIDC provider, PostgreSQL 16 and working Docker/Testcontainers. Use synthetic patients and laboratory data only.

From repository root:

```bash
mvn -B -ntp -fae -DskipTests install
mvn -B -ntp -f examples/diagnostic-laboratory/pom.xml clean verify -DskipITs=false
```

The current `WorkflowSmokeTest` checks **classpath only**. Do not count it as E2E evidence. Full acceptance requires a tagged `IndependentWorkflowIT` with real Liquibase installation and PostgreSQL transactions, a security-tested HTTP/API journey and a non-zero test count. See [11-example workflow acceptance matrix](../../docs/verification/EXAMPLE_WORKFLOW_ACCEPTANCE_MATRIX.md).

## Required clinical/operational lifecycle

1. Authorized external provider places a lab referral/order with subject identity, facility, order code, medical necessity and urgency.
2. LIS allocates a unique accession barcode, verifies patient/specimen and records collection, receipt, rejects/accepts and chain-of-custody timestamps.
3. Analyzer and QC metadata are recorded. A rejected Westgard run blocks report release and creates a corrective-action task.
4. Authorized laboratory scientist validates result, unit/reference range, panic thresholds, signature and amendment provenance.
5. Verified report appears in the requesting clinician's inbox; panic alerts escalate until human receipt/read-back and signed acknowledgment are independently persisted.
6. Result release is idempotent across message retries and service restarts. Status changes are audited, privacy-scoped, and linked to the original order.
7. Forms include an accession label, rejection sheet, QC log, signed result report, correction/addendum, and critical-value communication record. Reports include turnaround time, rejected specimens, QC exceptions, unresolved panic alerts and amendments.

**Must reject:** wrong facility or role, missing/invalid specimen, failed QC, unverified or noncritical read-back, duplicate results, revoked authorization, unavailable receiver and expired acknowledgment SLA.

No real patient data, production clinical claim, or standalone isolation certification until these steps have passed against a clean database and security/clinical review.
