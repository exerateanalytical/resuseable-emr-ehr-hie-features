# HealthCore — standalone clinical and operational workflow execution playbook

> **Status: INCOMPLETE / NOT RELEASE-CERTIFIED (21 November 2026).** The eleven standalone consumers exist but their classpath-only WorkflowSmokeTest files do not prove persistence or a functioning lifecycle. At this checkpoint, no independently executed end-to-end PostgreSQL Testcontainers workflow is evidenced for any example. The main-branch Java 25 CI is also failing. The following describes mandatory acceptance, **not functionality already verified**.

## 1. Scope and dependency boundary

HealthCore is a reusable SDK, not a deployable hospital application. Every example must run with **its own selected capability packs**, PostgreSQL migrations, OIDC/role checks, real domain services, records and reports. A shared quickstart host or a module unit test cannot replace an independent consumer test.

For the actual architectural intent, see [master patient journey](../workflows/PATIENT_JOURNEY_MASTER_SPEC.md), [workflow tests](../workflows/CLINICAL_WORKFLOW_TEST_MATRIX.md), and [standalone acceptance matrix](EXAMPLE_WORKFLOW_ACCEPTANCE_MATRIX.md).

## 2. Fail-closed developer execution

Requirements: JDK **25**, Maven **3.9+**, Python 3, Docker/Testcontainers and PostgreSQL 16 container support. Use synthetic identities only. The Java repair and devcontainer are still part of [draft PR #2](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/pull/2), not a certified release.

From the repository root:

~~~bash
bash scripts/run_standalone_workflow_verification.sh
~~~

The runner first calls the structural workflow-IT gate and **stops when any of the eleven suites is missing**. This is the expected result on current main. Once real suites exist and compile, it installs the SDK, then executes every example's Maven Failsafe verification. Structural presence and a green Maven exit code with zero tests are not accepted as workflow proof.

Targeted verification after the SDK can compile:

~~~bash
mvn -B -ntp -fae -DskipTests install
mvn -B -ntp -f examples/diagnostic-laboratory/pom.xml verify -DskipITs=false
mvn -B -ntp -f examples/outpatient-emr/pom.xml verify -DskipITs=false
mvn -B -ntp -f examples/hospital-his/pom.xml verify -DskipITs=false
~~~

Do not skip integration tests or mock the service whose real behavior is under test. Review generated Surefire/Failsafe XML and the green CI URL on the **same exact commit**. An actual running PostgreSQL database and real Liquibase migrations are required, not a mock repository.

## 3. Hospital journey acceptance — normal and exceptional paths

Create synthetic facility A, unauthorized facility B, one returning patient with an existing MPI identity, one new outpatient and an ESI-1 emergency patient. Use authorized registrar, triage nurse, cashier, physician, laboratory, radiology and pharmacy principals, each with a valid facility membership.

| Stage | Trigger | Required durable outcome | Mandatory negative assertion |
|---|---|---|---|
| Identity | Register returning patient | Same MPI patient identity, **new encounter**, provenance and match audit | Duplicate/concurrent registrations do not silently create extra identities |
| Check-in | Nurse records complaint, acuity, measured vital signs | Triage entry + priority + physician queue ticket | Unknown vitals remain unknown; never default to normal |
| Payment policy | Cashier/insurer settles or holds a routine service | Ledger and per-service financial clearance state | Unpaid routine fulfillment held; emergency stabilization never blocked for money |
| Consultation | Physician claims queue entry | Encounter-linked history/exam/SOAP, author identity, note version and signature | Wrong facility/role, expired credentials and unauthorized encounter denied |
| Orders | Physician signs lab/imaging/prescription | **Three independent specialty queues**, durable outbox + idempotent order routing | Duplicate event replay does not duplicate tickets, payments, specimens or dispensing |
| Laboratory | Specimen accession/QC/testing/verification | Barcode chain of custody, verified result and doctor inbox event | Failed QC or invalid sample cannot release; critical value requires human acknowledgment |
| Radiology | Schedule/acquire/report | Study metadata, signed report, versioned amendment and doctor inbox | PACS disabled is reported honestly; wrong-patient/unsigned results blocked |
| Pharmacy | Validate prescription, FEFO and stock | Dispense event, batch/expiry ledger, decrement and receipt | Recalled/expired stock and required witness checks fail closed |
| Result review | Doctor opens result task | Explicit review, read-back/acknowledgment, correlated to order and encounter | Notification submission is not delivery; missed critical result triggers escalation |
| Disposition | Doctor completes encounter or admits patient | Discharge/transfer note, reviewed results and auditable closed state | Outstanding critical acknowledgment or active required tasks block closure |
| Recovery | Restart between stages | Durable queue, task ownership, outbox replay and monotonic ticket sequences | Replayed callbacks or network interruptions do not cause loss or double processing |

**Emergency rule:** stabilization, urgent clinical review and communication of critical findings must proceed regardless of billing status.

**Security rule:** client-supplied facility headers never establish user authorization. All patient and department access derives from verified identity, membership, consent, permissions and audited exceptions.

## 4. Individual standalone app completion contracts

The minimum below is **in addition** to real application startup, permissions, tenant isolation, database assertions, restart, replay, forms/reports, audit, and migration testing.

| Example | Full real lifecycle | Required exception cases |
|---|---|---|
| standalone-pharmacy | Supplier → PO → receipt → batch/FEFO → prescription validation → dispense → receipt/stock ledger → recall | Expired or recalled lot, witness requirement, duplicate dispensing, foreign-tenant stock |
| diagnostic-laboratory | Referral → accession/barcode → sample custody → Westgard QC → measured result → sign/verify → clinician receipt | Rejected specimen, failed QC, critical-value read-back, unauthorized access |
| radiology-center | Referral → schedule/worklist → DICOM study **metadata** → imaging → signed report → amendment → clinician review | PACS unconfigured, wrong patient, unsigned report, missed critical finding |
| pharma-manufacturing | Approved formula → material quarantine/QC → dual-witness weigh → eBMR → IPC → QA release → serialization/recall | OOS, missing witness, deviation/CAPA, failed lot release; no patient dependencies |
| life-sciences-lims | Approved project → nonpatient sample → ELN/instrument/QC → scientific review → provenance-linked dataset | Missing ethics approval, instrument lineage break, unauthorized edits |
| biobank | Consent → biospecimen receipt → aliquot genealogy → controlled storage → approved request/MTA → release | Consent withdrawal, temperature excursion, orphan aliquot, wrong recipient |
| clinical-research | Protocol/IRB approval → eConsent → eligibility → visits/eCRF → queries/SAE → PI signoff → authorized export | Protocol deviation, adverse event escalation, withdrawal and unauthorized export |
| outpatient-emr | Patient identity → encounter/triage → exam/SOAP → orders → lab/Rx results → disposition/revisit | Duplicate MPI, unauthorized orders, missed critical result, emergency payment bypass |
| longitudinal-ehr | Two-provider versioned import → matching/provenance → consent → reconciliation → authorized summary | Wrong source, conflicting versions, consent revocation, cross-facility leakage |
| hospital-his | Registration → triage → billing policy → consultation → **parallel** lab/radiology/pharmacy → clinician ACK → admit/discharge | Concurrent tickets, unpaid service, late amended result, event loss and unsafe closure |
| regional-hie | Partner trust → identity matching → consent → FHIR/HL7 → acknowledgment/DLQ replay → audit | Wrong identity, duplicate replay, revoked consent, unavailable partner, unlawful export |

## 5. Minimum proof for every merged workstream PR

1. **Environment:** commit SHA, JDK/Maven, OS, PostgreSQL and Docker/Testcontainers versions, exact commands and real logs.
2. **Execution:** executed test count, passed/failed/skipped, actual Failsafe XML, migrations and CI run URL for the **same commit**.
3. **Persistence:** query assertion over durable clinical/business rows, event/outbox/inbox and transaction boundaries, including a fresh JDBC connection or restart.
4. **Security:** one cross-tenant/role negative HTTP/service test and consent where required.
5. **Reliability:** duplicate event delivery, retries, cancellation/compensation, worker restart and missing external dependency.
6. **Clinical/operational completeness:** signed document/report, exact patient/order provenance, positive and negative safety paths; a named human clinical reviewer for medical decisions.
7. **Isolation:** effective Maven dependency tree demonstrates excluded, unrelated starters are absent.

Missing one mandatory condition means **INCOMPLETE**. Classpath-only smoke tests, structural checks, mocked workflows, the 22 module suites and the eight pack fixture suites cannot be combined into a false claim of 11 independently working apps.

## 6. Development order and dispatch

Follow the [20-workstream ownership matrix](../agents/HEALTHCORE_20_WORKSTREAMS.md). First repair the Java reactor/tenant security; then persistence, workflow state machines, queues/billing/outbox, and finally each app-specific lifecycle and its tests. Keep PRs small, isolated and unmerged until tests and review pass.

Issues #3–#22 being created does **not** demonstrate that 20 agents are executing. GitHub's connected installation token returned forbidden on Copilot assignment. See [agent dispatch instructions](../agents/COPILOT_DISPATCH_SETUP.md) for manual UI or controlled user-token dispatch, and independently confirm linked coding sessions.
