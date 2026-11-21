# Standalone SDK consumer apps — real workflow acceptance matrix
**State: NOT VERIFIED / INCOMPLETE.** These are *required release acceptance tests*, not claims that the examples pass.

**Observed current state: 0/11 standalone examples have a demonstrated independent end-to-end Testcontainers workflow test.**

The 11 existing `WorkflowSmokeTest.java` files only assert a service `Class` is available. This proves neither application startup, schema migration, API correctness, domain workflows, clinical safety nor persistence. Such tests must never count as workflow acceptance.

| Independent example | Intended product | Required end-to-end synthetic workflow | Required negative/safety scenarios |
|---|---|---|---|
| [`examples/standalone-pharmacy`](../../examples/standalone-pharmacy/) | Pharmacy | Supplier receipt → batch/FEFO → prescription validation → dispense → stock decrement → recall | Controlled-substance witness; expired stock; cross-tenant stock access |
| [`examples/diagnostic-laboratory`](../../examples/diagnostic-laboratory/) | Laboratory | Referral/order → accession → specimen custody → Westgard QC → verified result → clinician ACK | QC failure; critical-result read-back; specimen rejection; unauthorized report |
| [`examples/radiology-center`](../../examples/radiology-center/) | Radiology | Referral → schedule/worklist → DICOM study metadata → acquisition → signed report → clinician review | Critical imaging escalation; amendment; PACS unavailable; unauthorized access |
| [`examples/pharma-manufacturing`](../../examples/pharma-manufacturing/) | Pharma MES/QMS | Master formula approval → material weigh/quarantine → eBMR → IPC → QA release → serialization | Deviation/CAPA; missing witness; failed OOS/QA; recall genealogy |
| [`examples/life-sciences-lims`](../../examples/life-sciences-lims/) | Life-science LIMS/ELN | Ethics-approved project → nonpatient sample → experiment/ELN → instrument/QC → release | No ethics approval; source/sample trace; immutable ELN edits |
| [`examples/biobank`](../../examples/biobank/) | Biobanking | Consent → biospecimen receipt → aliquot/storage → request/MTA → controlled release | Withdrawn consent; freezer excursion; genealogy consistency |
| [`examples/clinical-research`](../../examples/clinical-research/) | Research/EDC | Protocol/ethics → eConsent → eligibility → visits/eCRF → queries/SAE → PI sign → export | Protocol deviation; SAE; partial or withdrawn consent; unsanctioned export |
| [`examples/outpatient-emr`](../../examples/outpatient-emr/) | Outpatient EMR | Patient identity match → visit/triage → SOAP → CPOE/Rx → results review → disposition | Duplicate identities; critical results; emergency bypass; unauthorized order |
| [`examples/longitudinal-ehr`](../../examples/longitudinal-ehr/) | Longitudinal EHR | Cross-provider history import → provenance → reconciliation → authorized access → export | Patient matching conflicts; provenance; consent segmentation |
| [`examples/hospital-his`](../../examples/hospital-his/) | Hospital HIS | Arrival → triage → consultation/payment → parallel lab/RIS/pharmacy → clinician review → discharge | Financial hold exceptions; queue ordering; lost events; concurrent fulfillment |
| [`examples/regional-hie`](../../examples/regional-hie/) | Regional HIE | Partner trust → patient discover → consent → HL7/FHIR → ACK/DLQ → audit | Wrong patient/facility; duplicate replay; unavailable partner; revoked consent |

## Mandatory test contract per example
1. Build as a **separate consumer** with `healthcore-bom` and only the selected starter/pack dependencies. Prove prohibited modules absent from effective dependency tree (not only from POM text).
2. Start a real application with synthetic facility, RBAC/OIDC test principal, PostgreSQL 16 Testcontainers and real Liquibase migrations; use any other DB **only after** its compatibility is verified. The quickstart host is **not** a substitute.
3. Execute each numbered state transition **via public service contracts or HTTP**, not by manually mutating private entity fields or calling mock equivalents of the operations under test. Include durable data and actual query assertions.
4. Verify at least one normal outcome, one invalid clinical/business input, authorization/tenant mismatch, replay/idempotency, restart/reopen persistence, and workflow cancellation/retry.
5. For clinical examples, test state transitions and documents: clinical encounter, vital/triage record, order, signed report, clinician review and notification delivery/acknowledgment **only when those capabilities apply**. Record workflow engine event correlation IDs.
6. Verify documents/forms/report output with exact field requirements, role/locale and template version, not by counting title placeholders. No real patient data.
7. Emit Surefire XML, JaCoCo, database migration logs and a link to an actual GitHub Actions run for the same commit. A skipped test, mocked domain service, static class test or missing evidence means **NOT VERIFIED**.

## Suggested source code test shape
Add `src/test/java/.../IndependentWorkflowIT.java` using JUnit 5, `@Tag("workflow-it")`, Testcontainers PostgreSQL, a real application context, and persisted end-to-end assertions. Keep `WorkflowSmokeTest` as a small classpath check if useful, but **never describe it as the full workflow**.

At a minimum, exercise (1) independent app startup + migrations, (2) real domain lifecycle, (3) enforced negative guard, (4) persistence across fresh transactions or application restart, (5) API/event outcome; include security & interoperability tests when relevant.

## Release gate
`python3 scripts/check_example_workflow_evidence.py --enforce` is a **structural prerequisite only**. Its passing does not certify functionality. CI must actually run and pass the integration tests; clinical review must independently approve clinical rules. All 11 apps must have separate evidence before any "complete" claim.

## Clinical workflow principles
* One MPI patient identity per facility/domain of trust; repeated visits create encounters, not duplicate patients. Permit audited merge, provisional emergency identity and governed cross-facility matching.
* Payment is a configurable **service-level** gate; emergency stabilization and critical-result escalation are never delayed solely for payment.
* Orders must create idempotent specialty tasks with durable events, department queues, feedback/clinician inbox and patient notifications subject to clinical release policy.
* Parallel lab, imaging and pharmacy orders must not duplicate encounters, payment postings, or queue tickets.
* Critical results require a responsible clinician's acknowledgment and fallback escalation, not a message submitted status.
