# Pharmaceutical Manufacturing, QMS & Serialization Example (Zero Clinical/EMR/MPI)

> **PRE-PRODUCTION SDK reference consumer.** The existing `WorkflowSmokeTest` checks class availability only, not an end-to-end Pharmaceutical manufacturing workflow. **No full independent workflow IT has been verified.**

## Product scope
An independent Pharmaceutical manufacturing application must install only its selected HealthCore pack and mandatory core dependencies. Do not require a complete hospital system to launch.

## Mandatory persisted workflow
1. Approved formula.
2. raw-material quarantine.
3. eBMR.
4. in-process QC.
5. QA release.
6. serialization.

## Failure and authorization cases
- OOS hold.
- missing witness.
- expired equipment calibration.
- Test cross-tenant access denied, replay/idempotency, recovery, audit, and relevant consent/clinical role requirements.
- Use synthetic data; regulatory and clinical review are separate acceptance gates.

## Local development & verification
Install JDK 25, Maven 3.9+, Docker and PostgreSQL 16; or use the development container in [repair PR #2](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/pull/2).

```bash
# Run from repository root once the full SDK reactor compiles
mvn -B -ntp -fae -DskipTests install
# Run from this example folder; Failsafe must run the actual *IT.java tests
mvn -B -ntp verify -DskipITs=false
# Launch an application after its dependencies, migrations and security are configured
mvn -B -ntp spring-boot:run
```

The POM provides JUnit 5/Surefire, Failsafe integration tests and PostgreSQL Testcontainers. **Having dependencies does not prove the workflow is implemented.** The assigned engineer must implement `IndependentWorkflowIT.java` with `@Tag("workflow-it")`, real application context, migrations, persisted domain state, public API or service operations and negative cases. Class-only or mocked domain-service checks do not count.

**Authentication:** A verified JWT and authorized facility membership are required. `X-Facility-Id` alone is not trusted identity. No real patient data in fixtures.

**Release evidence:** successful database migration, domain transition receipts, relevant forms and reports, Testcontainers, Surefire/Failsafe XML, CI run URL and independent clinical review when applicable.

See [the 11-example acceptance matrix](../../docs/verification/EXAMPLE_WORKFLOW_ACCEPTANCE_MATRIX.md) and [20 workstreams](../../docs/agents/HEALTHCORE_20_WORKSTREAMS.md).
