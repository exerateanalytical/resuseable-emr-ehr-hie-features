# HealthCore — 20 independent workstreams and agent assignment board
**Date:** 2026-11-21. **Product:** composable healthcare & life-science SDK, not a single HIS. **Repository:** `exerateanalytical/resuseable-emr-ehr-hie-features`.

**Dispatch state:** These are issue-ready scopes. GitHub rejected assigning `copilot-swe-agent[bot]` with HTTP 422 on the connected account. Creating issues does **not** start agents. Once GitHub Copilot cloud agent is available for this account/repository, assign each issue through GitHub's *Assignees → Copilot* and select `fix/healthcore-java25-full-verification-20261120` as the base branch where appropriate. Verify each issue has a running session or linked Copilot PR, not merely an assignee label.

## Created GitHub work items — no agent sessions confirmed
All **20 GitHub issues #3–#22** were created on 2026-11-21. GitHub returned HTTP 422 for initial Copilot assignment and HTTP 403 when assigning afterwards. These are **not 20 active agents**.

| Workstream | Issue | Assignment status |
|---|---|---|
| W01 | [#3](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/issues/3) | Created, unassigned |
| W02 | [#4](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/issues/4) | Created, unassigned |
| W03 | [#5](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/issues/5) | Created, unassigned |
| W04 | [#6](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/issues/6) | Created, unassigned |
| W05 | [#7](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/issues/7) | Created, unassigned |
| W06 | [#8](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/issues/8) | Created, unassigned |
| W07 | [#9](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/issues/9) | Created, unassigned |
| W08 | [#10](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/issues/10) | Created, unassigned |
| W09 | [#11](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/issues/11) | Created, unassigned |
| W10 | [#12](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/issues/12) | Created, unassigned |
| W11 | [#13](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/issues/13) | Created, unassigned |
| W12 | [#14](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/issues/14) | Created, unassigned |
| W13 | [#15](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/issues/15) | Created, unassigned |
| W14 | [#16](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/issues/16) | Created, unassigned |
| W15 | [#17](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/issues/17) | Created, unassigned |
| W16 | [#18](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/issues/18) | Created, unassigned |
| W17 | [#19](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/issues/19) | Created, unassigned |
| W18 | [#20](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/issues/20) | Created, unassigned |
| W19 | [#21](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/issues/21) | Created, unassigned |
| W20 | [#22](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/issues/22) | Created, unassigned |

To start a Copilot cloud coding session manually, open the issue and use **Assignees → Copilot** when available, selecting the candidate branch `fix/healthcore-java25-full-verification-20261120` as the base. Confirm a linked Copilot draft PR or cloud session before marking agent active.

### Guardrails
- One PR per workstream, minimal file footprint, no force pushes, no direct merge to `main`, no editing another workstream's files without coordination. Review and rebase across PR #2.
- Never fake clinical, operational, or regulatory compliance. Use synthetic data, preserve financial consistency, RBAC, tenant isolation, consent, audit, emergency exceptions and immutable historical records.
- Update the affected documentation, OpenAPI, migrations, generated clients, README and runnable example **with the code**. Per-module unit tests alone do not prove cross-module automation.
- Agent PR must contain exact JDK/Maven versions, database type, commands, CI run ID, executed test count, coverage, known limitations, and security/clinical review needs. Failed/untested marked honestly.

| Workstream | Work owner / boundaries | Minimum acceptance |
|---|---|---|
| 01 — Java 25 reactor build and regression failures | pom.xml; existing Maven starter test source; .github/workflows | Green Java 25 full-reactor compile and test CI; no unresolved errors, evidence matrix per module |
| 02 — Facility isolation, OIDC roles, consent and audit | healthcore-security-audit; tenant-governance; tenant repository boundaries | Real HTTP/JWT negative tests; no trust in X-Facility-Id alone; post-transaction tenant cleanup |
| 03 — Canonical patient identity and visit journey | healthcore-starter-mpi; healthcore-starter-workflow-engine/journey | PostgreSQL integration with repeated arrival; no duplicate patient or encounter; ordered audit trail |
| 04 — Queue management and financial activation | healthcore-starter-queue-management; scheduling; uhc-billing | Parallel queue and payment concurrency, idempotent replays, restart recovery and no billing barrier to emergency stabilization |
| 05 — Clinical order/result/notification event orchestration | healthcore-starter-orders-results; radiology; LIS; pharmacy; workflow-engine | Real durable event-outbox tests; no manual calls between service stages, no notification counted delivered without provider proof |
| 06 — Standalone pharmacy example — complete operational flow | examples/standalone-pharmacy; pharmacy-commerce; pharmacy-inventory | Real PostgreSQL Testcontainers flow, clinic/hospital module isolation, controlled drug witness and payment opt-in negative tests |
| 07 — Standalone diagnostic laboratory example — complete LIS flow | examples/diagnostic-laboratory; diagnostics-core; lis-diagnostics | Persisted order-to-result workflow with failures for bad specimen/QC, clinician receipt proof, LIS-only pack isolation |
| 08 — Standalone radiology center example — complete RIS flow | examples/radiology-center; radiology; hl7-astm-dicom adapters | Real DB flows, no fake PACS binary features, PACS-off 501 and tenant/consent negative tests |
| 09 — Standalone pharmaceutical manufacturing example | examples/pharma-manufacturing; pharma-manufacturing; pharma-qms; pharma-serialization | Persistence, dual approvals, deviation/CAPA, lot genealogy and release-blocking tests; zero patient/EMR dependencies |
| 10 — Standalone life-sciences LIMS/ELN example | examples/life-sciences-lims; life-sciences-lims; genomics-research | Scientific non-patient sample and instrument lineage persisted with immutable review and independent sample tests |
| 11 — Standalone biobank example | examples/biobank; biobanking; biomedical-coldchain | Real inventory/aliquot and chain-of-custody tests, withdrawal blocks re-release, no orphan aliquots |
| 12 — Standalone clinical research / CRO example | examples/clinical-research; clinical-research; deidentification-research | Real study participant and eCRF lifecycle, privacy review gates, no unsupported regulatory-certification claims |
| 13 — Standalone outpatient EMR example | examples/outpatient-emr; emr-clinical; emergency-nursing; workflow-engine | Real multi-visit DB test and clinically supervised safety/authorization gates; no duplicate patient IDs |
| 14 — Standalone longitudinal EHR example | examples/longitudinal-ehr; mpi; hie-interop; patient-portal | Real record versioning and provenance assertions; cross-facility source attribution and privacy segmentation tests |
| 15 — Standalone HIS/reference host example | examples/hospital-his; healthcore-quickstart-host composition tests | PostgreSQL integration with automatic queues/events, critical result escalation, billing exceptions and restart recovery |
| 16 — Standalone regional HIE example | examples/regional-hie; hie-interop; interoperability-ihe; fhir-hie | Tenant-scoped R4/HL7 transaction, replay/idempotency and unauthorized export negative tests, no HIS inpatient dependencies |
| 17 — Android/iOS KMP offline clinical SDK | healthcore-mobile-kmp; Kotlin SDK Gradle CI | Android compile + real encrypted DB tests, iOS compilation on macOS, no silent default normal vitals or partial 5-rights checks |
| 18 — Database migrations, pack dependency isolation and release CI | Liquibase; packs/*; CI/Testcontainers; scripts/verification | Postgres16, SQLServer, MySQL execution evidence, all 8 packs independent, restore tests and generated evidence manifest |
| 19 — Clinical specialty, public-health and operational safety coverage | blood-bank; surgery-ot; emergency-nursing; workforce-roster; chw-homecare; disease-surveillance; public-health | Independent clinical rule test fixtures, exception routes, tenant guards, no clinical certification without signed reviewer |
| 20 — Documentation, README, integration and release evidence | README.md; docs/integrations; docs/verification; examples/*/README.md | Every one of 11 examples documents executable commands and real test outputs; remove overclaims, reconcile API paths, maintain clear release matrix |

## Execution tasks (full scope)
### 01. Java 25 reactor build and regression failures
**Primary ownership:** pom.xml; existing Maven starter test source; .github/workflows.
**Journey to implement/test:** Every Maven module compiles and executes tests; no skipped tests to hide failures.
**Acceptance evidence:** Green Java 25 full-reactor compile and test CI; no unresolved errors, evidence matrix per module.


### 02. Facility isolation, OIDC roles, consent and audit
**Primary ownership:** healthcore-security-audit; tenant-governance; tenant repository boundaries.
**Journey to implement/test:** Cross-tenant read/modify attempts denied; verified JWT facility memberships; consent/break-glass audited.
**Acceptance evidence:** Real HTTP/JWT negative tests; no trust in X-Facility-Id alone; post-transaction tenant cleanup.


### 03. Canonical patient identity and visit journey
**Primary ownership:** healthcore-starter-mpi; healthcore-starter-workflow-engine/journey.
**Journey to implement/test:** Returning patient match→one patient ID→new encounter→triage→consultation→results→disposition.
**Acceptance evidence:** PostgreSQL integration with repeated arrival; no duplicate patient or encounter; ordered audit trail.


### 04. Queue management and financial activation
**Primary ownership:** healthcore-starter-queue-management; scheduling; uhc-billing.
**Journey to implement/test:** Check-in→triage priorities→service queue→payment/insurance hold→release; emergency bypass.
**Acceptance evidence:** Parallel queue and payment concurrency, idempotent replays, restart recovery and no billing barrier to emergency stabilization.


### 05. Clinical order/result/notification event orchestration
**Primary ownership:** healthcore-starter-orders-results; radiology; LIS; pharmacy; workflow-engine.
**Journey to implement/test:** Signed lab/imaging/Rx order→automatic department task→signed result→inbox→clinical acknowledgment.
**Acceptance evidence:** Real durable event-outbox tests; no manual calls between service stages, no notification counted delivered without provider proof.


### 06. Standalone pharmacy example — complete operational flow
**Primary ownership:** examples/standalone-pharmacy; pharmacy-commerce; pharmacy-inventory.
**Journey to implement/test:** Supplier→PO→receipt→batch→FEFO→prescription validation→dispense→stock ledger; quarantine/recall.
**Acceptance evidence:** Real PostgreSQL Testcontainers flow, clinic/hospital module isolation, controlled drug witness and payment opt-in negative tests.


### 07. Standalone diagnostic laboratory example — complete LIS flow
**Primary ownership:** examples/diagnostic-laboratory; diagnostics-core; lis-diagnostics.
**Journey to implement/test:** Referral→order→specimen barcode→QC→analyzer/result→verification→critical-result escalation→release.
**Acceptance evidence:** Persisted order-to-result workflow with failures for bad specimen/QC, clinician receipt proof, LIS-only pack isolation.


### 08. Standalone radiology center example — complete RIS flow
**Primary ownership:** examples/radiology-center; radiology; hl7-astm-dicom adapters.
**Journey to implement/test:** Referral→worklist→appointment→DICOM study→acquisition→signed report→critical acknowledgment→amendment.
**Acceptance evidence:** Real DB flows, no fake PACS binary features, PACS-off 501 and tenant/consent negative tests.


### 09. Standalone pharmaceutical manufacturing example
**Primary ownership:** examples/pharma-manufacturing; pharma-manufacturing; pharma-qms; pharma-serialization.
**Journey to implement/test:** Approved formula→materials quarantine/QC→weigh witness→eBMR→IPC→QC/QA release→serialization→recall.
**Acceptance evidence:** Persistence, dual approvals, deviation/CAPA, lot genealogy and release-blocking tests; zero patient/EMR dependencies.


### 10. Standalone life-sciences LIMS/ELN example
**Primary ownership:** examples/life-sciences-lims; life-sciences-lims; genomics-research.
**Journey to implement/test:** Research protocol approval→scientific sample accession→experiment/ELN→instrument results→review→dataset provenance.
**Acceptance evidence:** Scientific non-patient sample and instrument lineage persisted with immutable review and independent sample tests.


### 11. Standalone biobank example
**Primary ownership:** examples/biobank; biobanking; biomedical-coldchain.
**Journey to implement/test:** Consent→biospecimen intake→aliquot genealogy→storage temp→approved request→release; withdrawal.
**Acceptance evidence:** Real inventory/aliquot and chain-of-custody tests, withdrawal blocks re-release, no orphan aliquots.


### 12. Standalone clinical research / CRO example
**Primary ownership:** examples/clinical-research; clinical-research; deidentification-research.
**Journey to implement/test:** Protocol→IRB/ethics→screening→eConsent→eCRF queries→AE→PI signoff→approved export.
**Acceptance evidence:** Real study participant and eCRF lifecycle, privacy review gates, no unsupported regulatory-certification claims.


### 13. Standalone outpatient EMR example
**Primary ownership:** examples/outpatient-emr; emr-clinical; emergency-nursing; workflow-engine.
**Journey to implement/test:** Existing patient→triage/vitals→consultation/SOAP→orders/Rx→results reviewed→follow-up/discharge.
**Acceptance evidence:** Real multi-visit DB test and clinically supervised safety/authorization gates; no duplicate patient IDs.


### 14. Standalone longitudinal EHR example
**Primary ownership:** examples/longitudinal-ehr; mpi; hie-interop; patient-portal.
**Journey to implement/test:** Import two clinical histories→provenance→consent→reconciliation/conflict→patient access→authorized summary export.
**Acceptance evidence:** Real record versioning and provenance assertions; cross-facility source attribution and privacy segmentation tests.


### 15. Standalone HIS/reference host example
**Primary ownership:** examples/hospital-his; healthcore-quickstart-host composition tests.
**Journey to implement/test:** Registration→triage→payment policy→consultation→lab, imaging and pharmacy in parallel→clinician review→admit/discharge.
**Acceptance evidence:** PostgreSQL integration with automatic queues/events, critical result escalation, billing exceptions and restart recovery.


### 16. Standalone regional HIE example
**Primary ownership:** examples/regional-hie; hie-interop; interoperability-ihe; fhir-hie.
**Journey to implement/test:** Partner onboarding→trusted identity match→consent→HL7/FHIR→DocumentReference→ACK/DLQ→audit.
**Acceptance evidence:** Tenant-scoped R4/HL7 transaction, replay/idempotency and unauthorized export negative tests, no HIS inpatient dependencies.


### 17. Android/iOS KMP offline clinical SDK
**Primary ownership:** healthcore-mobile-kmp; Kotlin SDK Gradle CI.
**Journey to implement/test:** Encrypted local chart→offline edit→conflict quarantine→auth renewal→sync→device revocation.
**Acceptance evidence:** Android compile + real encrypted DB tests, iOS compilation on macOS, no silent default normal vitals or partial 5-rights checks.


### 18. Database migrations, pack dependency isolation and release CI
**Primary ownership:** Liquibase; packs/*; CI/Testcontainers; scripts/verification.
**Journey to implement/test:** Install each pack alone→migrate→persist→restart→upgrade→rollback/restore→clean tenant isolation.
**Acceptance evidence:** Postgres16, SQLServer, MySQL execution evidence, all 8 packs independent, restore tests and generated evidence manifest.


### 19. Clinical specialty, public-health and operational safety coverage
**Primary ownership:** blood-bank; surgery-ot; emergency-nursing; workforce-roster; chw-homecare; disease-surveillance; public-health.
**Journey to implement/test:** Credential gates, transfusion mismatch, WHO surgical checklist, NEWS2 escalation, homecare referral, IDSR alert, vaccination recall.
**Acceptance evidence:** Independent clinical rule test fixtures, exception routes, tenant guards, no clinical certification without signed reviewer.


### 20. Documentation, README, integration and release evidence
**Primary ownership:** README.md; docs/integrations; docs/verification; examples/*/README.md.
**Journey to implement/test:** New developer picks pack→installs deps→starts synthetic environment→runs real workflow→finds OpenAPI, forms, reports and troubleshooting.
**Acceptance evidence:** Every one of 11 examples documents executable commands and real test outputs; remove overclaims, reconcile API paths, maintain clear release matrix.


## Dependency/reconciliation order
1. Stabilize Java 25 reactor, security and pack isolation (01, 02, 18) **before** releasing standalone profiles.
2. Coordinate canonical workflow architecture (03–05) before finalizing outpatient/HIS integration (13, 15).
3. Domain examples (06–16) own their tests and examples, reuse canonical services, and do not duplicate runtime engines.
4. Android/iOS (17), specialty/public-health safety (19) and docs/evidence (20) run independently but need final reconciliation.
5. Merge only reviewed passing PRs after dependency-aware rebase, then run **full** matrix on candidate merge commit (unit, integration, pack isolation, multi-DB, mobile).
