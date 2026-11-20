# OPES HealthCore — Centralized Testing Module (`healthcore-testing`)

**Artifact**: `com.opeshealth.healthcore:healthcore-testing:1.0.0`

Provides the centralized **`HealthCoreDataFixture`** utility and **`IsoHl7StandardSampleData`** catalog to seed databases with standardized, deterministic, zero-PII **ISO & HL7-compliant** sample data for verifying documented workflows in isolation or across the full Reference Hospital suite.

## Covered Consoles & Backend Starters
- **8 Reference Hospital Consoles**:
  1. Radiology & PACS Modality Worklist (DICOM CT/CR, MPPS, signed critical finding report)
  2. Emergency Triage & Inpatient Nursing (ESI-2 triage, NEWS2 escalation, discharge readiness gate)
  3. Laboratory Accession, Reflex QC & Panic-Value Read-Back (LOINC `2823-3` critical potassium, `24323-8` barcoded specimen)
  4. Blood Bank Hemovigilance & Operating Theater / PACU (ISBT-128 `O-` PRBC unit, ABO/Rh compatibility, WHO surgical checklist, Modified Aldrete score)
  5. Patient Portal & Scoped Proxy Access (ISO 639-1 `fr` account, scoped proxy grant, care-team thread)
  6. Governed Clinical AI Workbench (tenant policy enablement, SHA-256 redacted `DRAFT_PENDING_ATTESTATION` draft, clinician attestation)
  7. Hospital Analytics, Quality Measures & Cohort Export (`CMS165v12`, k-anonymized DPO-approved cohort export)
  8. Full Tenant/MPI/EMR Foundation (`ISO 3166-1 CM`, `ISO 4217 XAF`, HL7 FHIR R4 administrative gender & encounter classes)
- **2 New Backend Starters (Seeding Supported in Complete Isolation)**:
  - `healthcore-starter-integration-ops` — HL7 FHIR R4 & HL7 v2.5.1 partner profiles, outbound FHIR Bundle & `ORM^O01` messages, DLQ failure & audited replay
  - `healthcore-starter-revenue-coding-claims` — `XAF` payer tariffs, ICD-10/CPT/DRG clinical coding & approval, eligibility/prior auth, X12 837P / FHIR claim submission & X12 835 ERA remittance

## Isolated Usage Example
```java
// Seed ONLY the Integration Ops starter in isolation (no hospital/EMR modules required)
HealthCoreDataFixture fixture = HealthCoreDataFixture.builder()
        .integrationOpsService(integrationOpsService)
        .build();
var seeded = fixture.seedIntegrationOpsStarter(facilityId);
```

## Programmatic README Workflow Verification (`WorkflowVerificationService`)
`WorkflowVerificationService` invokes `HealthCoreDataFixture` seeding and then programmatically triggers and verifies the core workflows documented in each module's README, logging structured SLF4J `[PASS]` / `[FAIL]` outcomes and producing a `WorkflowVerificationReport` (`assertAllPassed()`, `toMarkdownSummary()`):

```java
WorkflowVerificationService verifier = WorkflowVerificationService.builder(fixture)
        .integrationOpsService(integrationOpsService)
        .build();
var result = verifier.seedAndVerifyIsolatedWorkflow("integration-ops", facilityId, null, null);
```

## Master Patient Journey, Queues & 22-Module Workflow Alignment
- **Optional Master Patient Journey & Departmental Queues**: Wire `.patientJourneyService(...)` and `.queueManagementService(...)` on `HealthCoreDataFixture.builder()` to invoke `seedMasterPatientJourneyAndQueues(facilityId, patientId, encounterId)` (or include `"patient-journey-queues"` automatically in `seedAllReferenceConsolesAndStarters()`).
- **22-Module Versioned JSON Workflow Verification**: `PatientJourneyEndToEndAcceptanceTest` verifies all 6 core patient journey blueprints (`OPD_CONSULTATION_FLOW` v2.0.0 + 5 subworkflows), all 22 module versioned JSON definitions (`docs/workflows/definitions/*.v1.json` + `<module>/src/main/resources/workflows/*.v1.json`), canonical `CanonicalWorkflowEventType` alignment, and `workflow-registry.v1.yaml` completeness.

All starter dependencies in `healthcore-testing/pom.xml` are declared `<optional>true</optional>`, guaranteeing zero transitive coupling when imported in `<scope>test</scope>` and allowing all 8 Industry Capability Packs to initialize cleanly in isolation (verified by `IndustryPackFixtureIsolationTest` and `.github/workflows/pack-isolation-and-test-suites.yml`).
