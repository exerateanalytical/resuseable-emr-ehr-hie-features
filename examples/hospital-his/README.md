# Composable Hospital Information System (HIS) Example

> **PRE-PRODUCTION.** This is a composable HealthCore SDK consumer, not a finished or clinically certified Composed hospital HIS. The original classpath-only `WorkflowSmokeTest` is not proof of functionality.

## Required independent workflow
1. Arrival.
2. triage and vitals.
3. billing policy.
4. consultation.
5. parallel lab/imaging/pharmacy tasks.
6. clinician inbox.
7. admission/discharge.

## Required negative/security outcomes
- Emergency billing bypass.
- duplicate order replay.
- clinician critical acknowledgment.
- restart resilience.
- Test tenant and authorized clinician/partner membership, consent, durable state, errors and idempotent replay.
- Verify report/doc/encounter provenance, actual database state and audit trails, not UI placeholders.

## Developer setup
Requires JDK 25, Maven 3.9+, Docker/Testcontainers and PostgreSQL 16. [Development container and Java 25 verification scripts](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/pull/2) remain on repair PR #2 until merged.

```bash
# At repository root, after fixing full-reactor compile failures
mvn -B -ntp -fae -DskipTests install
# In this example directory — Maven Failsafe executes *IT.java
mvn -B -ntp verify -DskipITs=false
# Only after migrations and authentication are provisioned
mvn -B -ntp spring-boot:run
```

This POM now provides compiler, Surefire, Failsafe, Spring Boot Maven plugin and PostgreSQL Testcontainers test dependencies. **Actual end-to-end workflow integration tests are still missing and must be implemented** as `@Tag("workflow-it")` `IndependentWorkflowIT` using a real application context, persisted transactions and negative cases. The domain services cannot be mocked as a substitute for workflow execution.

Use only synthetic data. HTTP requires verified OIDC/JWT role/facility authorization; a client-controlled `X-Facility-Id` header cannot authenticate users. Publish green Surefire/Failsafe, migration and CI evidence and clinical/security review requirements.

See [11 independent app acceptance matrix](../../docs/verification/EXAMPLE_WORKFLOW_ACCEPTANCE_MATRIX.md) · [20 workstreams](../../docs/agents/HEALTHCORE_20_WORKSTREAMS.md).
