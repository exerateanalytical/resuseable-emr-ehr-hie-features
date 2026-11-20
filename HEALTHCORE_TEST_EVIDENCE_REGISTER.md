# OPES HealthCore — Test Evidence Register

> **Execution Environment Disclosure**: This workspace container does not have a local JDK 25, Maven, or Docker daemon installed. Therefore, this register strictly separates **(A) Implemented Test Suites & Statically Verified Checks (Executed Locally)** from **(B) Full JDK 25 / Maven / Multi-DB Testcontainers Execution (Executed in External CI via `.github/workflows/ci.yml`)**. No unexecuted runtime test is claimed as executed here.

## A. Implemented Automated Test Suites (61 JUnit 5 Suites + 2 KMP Suites + 11 Example Smoke Tests)

### 1. New Universal SDK & Industry Pack Isolation Test Suites (10 New Suites)
| Test Suite | Module | Verified Behavior / Governance Rules |
|---|---|---|
| `CapabilityPackIsolationTest` | `healthcore-persistence-core` | Recursively resolves all 8 `packs/healthcore-pack-*/pom.xml` transitive graphs against `pack-manifest.json` `requiredModules` and `forbiddenTransitiveModules` |
| `PharmacyCommerceServiceTest` | `healthcore-starter-pharmacy-commerce` | Expired goods rejection, FEFO lot selection, controlled-substance independent witness gate, recall quarantine auto-skip |
| `DiagnosticsCoreServiceTest` | `healthcore-starter-diagnostics-core` | Referring partner intake, specimen rejection blocking report release, TAT SLA breach detection, ISO 15189 incident logging |
| `PharmaManufacturingServiceTest` | `healthcore-starter-pharma-manufacturing` | Maker-checker formula approval, line clearance gate before dispensing, dual-witness weighing, yield reconciliation, QA batch disposition & genealogy |
| `PharmaQmsServiceTest` | `healthcore-starter-pharma-qms` | Controlled SOP maker-checker, OOS hold blocking CoA issuance, CoA e-signature, critical CAPA effectiveness gate |
| `PharmaSerializationServiceTest` | `healthcore-starter-pharma-serialization` | GS1 AI string commissioning, 18-digit SSCC aggregation, cold-chain excursion hold blocking release, quarantine block, batch recall stopping shipments |
| `LifeSciencesLimsServiceTest` | `healthcore-starter-life-sciences-lims` | Ethics/IRB approval gate before sample registration, non-patient scientific samples (`CELL_LINE`), ELN maker-checker lock & immutability |
| `BiobankingServiceTest` | `healthcore-starter-biobanking` | Consent gate on intake, parent-child aliquot volume/freeze-thaw genealogy, MTA fulfillment, cascading donor consent withdrawal block |
| `GenomicsResearchServiceTest` | `healthcore-starter-genomics-research` | Sequencing Q30 >= 80% QC gate, SHA-256 VCF provenance verification, DAC dataset release |
| `ClinicalResearchServiceTest` | `healthcore-starter-clinical-research` | Eligibility + eConsent gate, eCRF open-query lock block, PI signature lock, SAE expedited reporting flag, de-identified CDISC SDTM `DM` export |

### 2. Pre-Existing Core, Clinical, Hospital, Revenue, Interop & Multi-DB Suites (51 JUnit Suites + 2 KMP Suites)
- Includes `MultiDbTestcontainersIT` (PostgreSQL 16, MSSQL 2022, MySQL 8.4 clean install + idempotent upgrade), `AuditRemediationRegressionTest`, `ExtendedWorkstationAndDrVerificationTest`, and all domain starter unit tests across all 41 original modules.

### 3. Minimal Consumer Example Smoke Tests (11 Examples)
- `examples/*/src/test/java/com/example/healthcore/*/WorkflowSmokeTest.java` across all 11 standalone consumer projects.

## B. Local Static & Structural Verification Executed in This Workspace
1. **XML Well-Formedness Audit**: Parsed 100% of `pom.xml` and Liquibase `.xml` changelogs (all 58 reactor modules + 8 pack masters + 11 example POMs) with Python `xml.etree.ElementTree`.
2. **JSON Schema/Syntax Audit**: Parsed 100% of `packs/*/src/main/resources/META-INF/healthcore/pack-manifest.json`.
3. **Transitive Pack Isolation Graph Verification**: Executed transitive closure verification over all 8 pack POMs + all 58 module POMs matching `CapabilityPackIsolationTest` logic — **0 forbidden cross-industry dependencies detected**.
4. **Java Internal Import & Type Resolution Audit**: Verified all internal `com.opeshealth.healthcore.*` imports across all new and existing Java sources resolve to real public classes/interfaces/records.
5. **No-TypeScript / Pure Java Guardrail**: Confirmed 0 `package.json`, `tsconfig.json`, or `vite.config.ts` files exist in the repository.

## C. External CI Verification Required for Release Sign-Off
Run on a JDK 25 + Maven 3.9+ + Docker runner (automated in `.github/workflows/ci.yml`):
1. `mvn -B -T 1C clean verify` (compiles all 58 modules & executes all 61 JUnit suites including `CapabilityPackIsolationTest`).
2. `mvn -B -pl healthcore-quickstart-host -Dtest=MultiDbTestcontainersIT test` (PostgreSQL 16, SQL Server 2022, MySQL 8.4).
3. Independent build of each `examples/*` project after `mvn install -DskipTests`.
