# OPES HealthCore — Complete Module Inventory

**Group ID**: `com.opeshealth.healthcore` | **Version**: `1.0.0` | **Runtime Baseline**: Pure Java 25, Spring Boot 4.0, Liquibase, PostgreSQL 16+ / SQL Server 2022 / MySQL 8.4+ | **Total Reactor Modules**: **58** (+ `healthcore-mobile-kmp` companion + 11 standalone `examples/*`).

## 1. Platform Core & Governance (4 Modules)
| Artifact ID | Category | Purpose |
|---|---|---|
| `healthcore-bom` | BOM | Centralized Maven Bill of Materials for all starters, packs, Spring Boot 4, FHIR/HL7, Liquibase, DB drivers, and Testcontainers |
| `healthcore-persistence-core` | Core | UUIDv7 generator, multi-DB Liquibase base, Hibernate `facilityFilter`, domain-neutral SDK primitives (`ElectronicSignatureSpi`, `TraceableLotPrimitive`, `CapabilityPackManifestContract`) |
| `healthcore-security-audit` | Core | OIDC/JWT security, RBAC permissions, `FacilityContext`, break-glass controls, tamper-evident `@ClinicalAudit` chain |
| `healthcore-starter-tenant-governance` | Governance | Multi-facility tenant lifecycle, licensing/tier quotas, cross-facility isolation policies |

## 2. Standalone Pharmacy & Diagnostics Starters (2 New Isolated Starters + 4 Clinical Starters)
| Artifact ID | Industry Isolation | Purpose |
|---|---|---|
| `healthcore-starter-pharmacy-commerce` | Zero EMR/LIS/HIS | Standalone retail/chain pharmacy: drug catalog, PO/goods receipt, FEFO lot inventory, controlled-drug witness, recall quarantine, near-expiry alerts |
| `healthcore-starter-diagnostics-core` | Zero Pharmacy/EMR/HIS | Modality-neutral ISO 15189 diagnostics core: referring partners, order accessioning, rejection/recollection, TAT SLA monitoring, quality incidents |
| `healthcore-starter-radiology` | Zero LIS/EMR/HIS | RIS orders, DICOMweb study metadata, structured radiology reporting |
| `healthcore-starter-lis-diagnostics` | Hospital/Lab | Clinical laboratory orders, specimens, verification wrapper |
| `healthcore-starter-pharmacy-inventory` | Hospital/Pharmacy | Hospital pharmacy dispensing & inventory wrapper |
| `healthcore-starter-pharmacy-lis` | Hospital Combined | Legacy combined hospital pharmacy + LIS starter (preserved for backward compatibility) |

## 3. Pharmaceutical Manufacturing, QMS & Serialization Starters (3 New Isolated Starters)
| Artifact ID | Industry Isolation | Purpose |
|---|---|---|
| `healthcore-starter-pharma-manufacturing` | Zero MPI/EMR/HIS | Product master, maker-checker Master Formula/BOM, eBMR/eBPR, line clearance, dual-witness weighing, IPC, yield reconciliation, QA batch disposition, genealogy |
| `healthcore-starter-pharma-qms` | Zero MPI/EMR/HIS | Controlled SOP versioning, QC testing & OOS hold, Certificates of Analysis (CoA), deviations/CAPA effectiveness verification |
| `healthcore-starter-pharma-serialization` | Zero MPI/EMR/HIS | GS1 DataMatrix commissioning, 18-digit SSCC aggregation, warehouse quarantine/release, cold-chain excursion holds, recalls, shipments |

## 4. Life Sciences, Biobanking, Genomics & Clinical Research Starters (4 New Isolated Starters + 1 Research Bridge)
| Artifact ID | Industry Isolation | Purpose |
|---|---|---|
| `healthcore-starter-life-sciences-lims` | Zero MPI/EMR/HIS | Research studies, Ethics/IRB gate, non-patient scientific sample registry, chain of custody, versioned & locked ELN experiments |
| `healthcore-starter-biobanking` | Zero MPI/EMR/HIS | ISO 20387 biospecimens, donor consent & revocation, freezer/rack/box/slot mapping, excursion quarantine, aliquot genealogy, MTA fulfillment, disposal |
| `healthcore-starter-genomics-research` | Zero MPI/EMR/HIS | Sequencing library prep & Q30 QC gate, bioinformatics pipeline/reference genome provenance, `VariantStoreAdapterSpi`, DAC dataset release |
| `healthcore-starter-clinical-research` | Zero MPI/EMR/HIS | ICH E6(R3) & CDISC EDC: pseudonymous subject screening/eConsent, SDTM eCRF & query lock, SAE expedited flagging, de-identified CDISC SDTM export |
| `healthcore-starter-deidentification-research` | Bridge | Safe-harbor de-identification & cohort export bridge from clinical EHR to research |

## 5. EMR, EHR, Hospital Operations, Billing, Interoperability & Public Health Starters (28 Modules)
`healthcore-starter-mpi`, `healthcore-starter-terminology-cdss`, `healthcore-starter-scheduling`, `healthcore-starter-emr-clinical`, `healthcore-starter-orders-results`, `healthcore-starter-workflow-engine`, `healthcore-starter-patient-portal`, `healthcore-starter-templates-dashboards`, `healthcore-starter-hospital-ops`, `healthcore-starter-emergency-nursing`, `healthcore-starter-surgery-ot`, `healthcore-starter-blood-bank`, `healthcore-starter-biomedical-coldchain`, `healthcore-starter-crvs-mortuary`, `healthcore-starter-workforce-roster`, `healthcore-starter-telemedicine`, `healthcore-starter-uhc-billing`, `healthcore-starter-revenue-coding-claims`, `healthcore-starter-hie-interop`, `healthcore-starter-fhir-hie`, `healthcore-starter-hl7-astm-dicom`, `healthcore-starter-interoperability-ihe`, `healthcore-starter-integration-ops`, `healthcore-starter-api-product`, `healthcore-starter-offline-sync`, `healthcore-starter-public-health`, `healthcore-starter-chw-homecare`, `healthcore-starter-programs-immunization`, `healthcore-starter-disease-surveillance`, `healthcore-starter-analytics`, `healthcore-starter-clinical-ai`.

## 6. Composable Industry Capability Packs (8 Modules under `packs/`)
`healthcore-pack-pharmacy`, `healthcore-pack-diagnostics`, `healthcore-pack-pharma-manufacturing`, `healthcore-pack-life-sciences`, `healthcore-pack-emr`, `healthcore-pack-ehr`, `healthcore-pack-his`, `healthcore-pack-hie`.

## 7. Reference Host, Mobile Client & Standalone Examples
- `healthcore-quickstart-host` — Full reference hospital workstation host (17 Pure Java Thymeleaf consoles).
- `healthcore-mobile-kmp` — Kotlin Multiplatform offline-first Android/iOS client SDK.
- `examples/*` (11 projects) — Independently runnable minimal Spring Boot 4 consumer apps proving true module isolation.
