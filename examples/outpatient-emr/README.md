# Outpatient & Specialty Clinic EMR Example (Zero Inpatient/Mortuary)

> **SDK reference consumer — pre-production and NOT verified end-to-end.** Current `WorkflowSmokeTest.java` only checks Java class availability. This example is not a finished Standalone outpatient EMR product.

## Independent domain boundary
Developers may use the corresponding HealthCore starter/pack without adopting a full hospital system. Require only documented dependencies and verify the effective Maven graph.

## Clinical/operational workflow required
1. MPI returning patient.
2. new encounter.
3. triage/vitals.
4. clinician note.
5. CPOE/Rx.
6. result review.
7. disposition.

## Mandatory failures and safeguards
- Patient duplication.
- critical result.
- emergency billing override.
- Facility/role isolation, idempotent replay, persisted records across fresh transactions, consent, audit and real report/form outputs where applicable.
- No identifiable patient or research-participant records in fixtures; clinical and regulatory review is separate from a green build.

## Developer build and test
JDK 25, Maven 3.9+, Docker/PostgreSQL 16 required. The [reproducible Java 25 development container](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/pull/2) is on the repair branch until merged.

```bash
# From the repository root after the SDK reactor is compilable
mvn -B -ntp -fae -DskipTests install
# From this example project: run tests including Maven Failsafe *IT.java
mvn -B -ntp verify -DskipITs=false
# Start the example only after successful dependencies and tenant/security configuration
mvn -B -ntp spring-boot:run
```

This POM now declares Spring Boot Maven plugin, Maven compiler, JUnit 5 Surefire, Failsafe and PostgreSQL Testcontainers for building genuine independent workflow tests. **These dependencies are scaffolding, not proof of working workflows.** A `@Tag("workflow-it")` `IndependentWorkflowIT` must execute the real domain transactions, migrations, workflows, negative tests and event/report outputs. Do not replace the domain service under test with a mock.

HTTP API access requires verified JWT role/facility membership; `X-Facility-Id` alone is not authorized. Evidence: green Surefire/Failsafe XML and CI logs, actual workflow and database state checks, documentation updates, independent clinical review where required.

[11 independent app acceptance matrix](../../docs/verification/EXAMPLE_WORKFLOW_ACCEPTANCE_MATRIX.md) · [20 workstreams](../../docs/agents/HEALTHCORE_20_WORKSTREAMS.md).
