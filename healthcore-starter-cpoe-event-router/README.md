# HealthCore Clinical Order Outbox → Automatic Departmental Routing

**Status:** opt-in reference adapter, not clinically certified. Java 25, Spring Boot 4, actual PostgreSQL and external broker evidence must pass before deployment.

## Problem solved

The EMR already persists CPOE orders and emits `MedicationOrderedEvent` / `DiagnosticOrderedEvent` into the transactional outbox. The workflow and queue starter already support departmental routing, payment holds and lab/radiology/pharmacy worklists. But a deployed service needs an **authenticated, durable link** between those components. This module is that optional link for the Spring in-process outbox publisher.

The module depends on `healthcore-starter-emr-clinical` and `healthcore-starter-journey-queue-bridge`. It does not alter the generic workflow or queue packages and does not introduce circular dependencies.

## Enable explicitly — verification is mandatory

```yaml
healthcore:
  cpoe-routing:
    enabled: true
```

You MUST also supply a real `CpoeOrderEventRoutingAutoConfiguration.ClinicalOrderRoutingAuthorizationSpi` bean. **When enabled without this provider, Spring startup must fail on the missing bean; it must never assume an unsigned order is authorized.** The interface returns a `VerifiedRoutingAuthorization` with:

- Institution-verified immutable clinician signature evidence ID and independent clinical privilege verification
- A verified billing/payer policy evidence ID, facility-specific `FacilityBillingPolicyConfig`, actual billed/paid amounts, payer approval/credit/waiver/sponsorship flags
- Explicit **clinically authorized** emergency exception; never derive a payment bypass solely from a `STAT` priority string
- Optional authenticated external pharmacy routing destination; external pharmacy orders do not enter an internal dispensing worklist

The library cannot infer trusted clinical/financial evidence from a client request or outbox JSON. There is **no default permissive verification provider**. An integrator must implement institutional signature/credential and payer/billing checks, including revocation, clock/time, role/consent, audit and emergency governance.

## Event pipeline

1. A clinician with proper authority completes CPOE safety checks in `EmrClinicalWorkflowService.placeOrderWithCdss`, which saves the actual `ClinicalOrderEntity` and an outbox event in one DB transaction.
2. The event has `aggregateType=ClinicalOrder`, `aggregateId=orderId`, a **tenant facility ID**, and one of `MedicationOrderedEvent` or `DiagnosticOrderedEvent`. Its JSON includes `orderId`, `facilityId`, `patientId`, `encounterId`, `orderCategory`, `orderedBy`.
3. The existing `OutboxRelayService` publishes a typed `HealthCoreDomainEvent` on Spring's in-process event bus. The new `CpoeOrderEventListener` only handles the two accepted CPOE event types; it ignores unrelated system events.
4. It reloads the **persisted clinical order** using the event aggregate ID and verifies active state, facility identity, patient/encounter, order category, responsible author and the event payload against the stored row. Forged or stale event contents fail closed.
5. The institution's `ClinicalOrderRoutingAuthorizationSpi` confirms signed clinical authority and actual financial clearance evidence. When missing, expired, invalid or inconsistent, routing is rejected and the event must be retried/remediated; no department queue is released.
6. The adapter calls `PatientJourneyOrchestratorService.routeSignedOrder` with a deterministic `CPOE-ROUTE-{orderId}` idempotency key. The queue bridge creates the corresponding laboratory, radiology, pharmacy, procedure or referral worklist and ties it to the original order.
7. The department remains on financial hold unless a *verified* clearance permits activation. Later cashier/insurer confirmation releases the **same** queue task. Completion is prevented until the accountable department worker actually completes it.

## Current boundaries / clinical release gates

- **Outbox publisher:** this is a synchronous Spring event-bus consumer. Kafka/RabbitMQ installations require a durable, separately wired consumer with the same replay and provenance guards. The relay currently catches publisher errors and marks retryable failures; verify backoff, DLQ and transactions with real failures on PostgreSQL.
- **Exactly-once:** not claimed. Redelivery is safe through deterministic route keys and database uniqueness; first-write races still require real PostgreSQL concurrent tests.
- **Clinical signature:** the current EMR order entity has an author and status but not a signed-evidence FK in this adapter. Institution policy must verify the signature independently before approving an order. Returning a free-text ID without verification is NOT adequate for production.
- **Financial clearance:** the institutional adapter must not fake invoice amounts, allow an unsigned emergency override, or treat missing billing data as payment-not-required. Default policy alone is not proof of coverage.
- **Actual departmental completion:** queue creation does NOT prove specimen receipt, analyzer QC, signed result, radiology report, dispensed medication, reconciliation or clinician acknowledgment. Those modules still require full transactional integration and clinical acceptance.
- **Security:** validate JWT principal/facility authorization on upstream commands and provider-signed events at broker boundaries; this listener verifies persisted domain identity but does not implement patient HTTP login or provider credentials.
- **Standalone IT:** `examples/outpatient-emr/IndependentWorkflowIT` contains a **test-only synthetic policy** and a direct typed Spring event-publish simulation. This demonstrates the same listener contract, but not a running production outbox scheduler or signed clinical/payer evidence. JDK25+PostgreSQL CI is not yet green.

## Build commands

```bash
mvn -B -ntp -pl healthcore-starter-cpoe-event-router,packs/healthcore-pack-emr -am clean install -DskipITs=true
mvn -B -ntp -f examples/outpatient-emr/pom.xml clean verify -DskipITs=false
```

The branch-specific assurance workflow `.github/workflows/cpoe-event-routing-branch-assurance.yml` is designed to run these on a Temurin JDK 25 GitHub Actions runner, report actual Failsafe XML evidence, and fail if the independent workflow IT is absent or skipped. It has **not been proven to run** yet.
