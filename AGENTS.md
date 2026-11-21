# HealthCore SDK contributor/agent contract

Read [the scoped workstreams](docs/agents/HEALTHCORE_20_WORKSTREAMS.md), the relevant GitHub issue, and [the 11 example acceptance requirements](docs/verification/EXAMPLE_WORKFLOW_ACCEPTANCE_MATRIX.md).

**HealthCore is a reusable healthcare and life-science SDK, not a finished hospital system.** Do not duplicate MPI, orders, billing ledgers, workflow engines, clinical services or hospital-only dependencies.

Java 25 / Spring Boot 4, reusable extension contracts, OpenAPI, tenant isolation, consent, audit, idempotency, transactional outbox/inbox, and safe clinical failure handling are mandatory. Use synthetic-only records. Do not fake clinical correctness or claim regulatory certification.

**One task = one bounded PR.** Work from candidate repair branch `fix/healthcore-java25-full-verification-20261120` when applicable and reconcile with draft PR #2. Never force-push another agent's code. No direct merges to `main` and no bypass/skipping failing tests.

For every standalone example, replace classpath-only proof with actual persisted workflow integration using JUnit 5, real PostgreSQL Testcontainers, negative safety cases, facility-authorization checks, event and reconciliation assertions, restart/retry and API contracts as applicable. A mock of the service under test does not count as an integration test.

Update the affected module README, integration guide, migrations, OpenAPI examples, and reproducible test-evidence register. Provide real CI run links for the exact candidate commit. Mark known gaps rather than fabricating success.
