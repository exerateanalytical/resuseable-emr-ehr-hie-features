# Longitudinal Multi-Facility EHR & IPS Portability Example

> **PRE-PRODUCTION.** This is a composable HealthCore SDK consumer, not a finished or clinically certified Longitudinal EHR. The original classpath-only `WorkflowSmokeTest` is not proof of functionality.

## Required independent workflow
1. Multiple-source clinical record import.
2. provenance.
3. medication reconciliation.
4. patient consent.
5. conflict review.
6. authorized summary export.

## Required negative/security outcomes
- Record-source conflicts.
- cross-facility ID mismatch.
- confidential segment authorization.
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
