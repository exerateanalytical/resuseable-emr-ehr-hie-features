# OPES HealthCore — Developer Integration Guide Index

## 1. Developer Portal & Architectural Guides
- **Quickstart (Modes A, B & C)**: `docs/getting-started/QUICKSTART.md`
- **Complete 58-Module Catalog**: `docs/modules/MODULE_CATALOG.md`
- **Workflow State Machines & Transactional Contracts**: `docs/workflows/WORKFLOW_STATE_MACHINES.md`
- **Structured Form Templates & Report Definitions**: `docs/workflows/FORM_AND_REPORT_DEFINITIONS.md`
- **Cameroon, CEMAC & Regional Adapters + 5-Stage Standards Model**: `docs/standards/CAMEROON_AND_REGIONAL_ADAPTERS.md`
- **Versioning, Upgrades, Troubleshooting & Contribution Guide**: `docs/operations/VERSIONING_UPGRADES_AND_CONTRIBUTION.md`
- **External Multi-Language & Device Integration Guide**: `docs/integrations/EXTERNAL_APPLICATIONS_INTEGRATION_GUIDE.md` (Java/Spring Boot, Python/FastAPI, .NET/ERP, web frontends, Kotlin Multiplatform `healthcore-mobile-kmp`, HL7 v2/ASTM, DICOMweb, IoT/ERP bridges)

## 2. Industry Capability Pack Integration Guides (20-Section Standard Format)
| Pack | Guide Path | Standalone Example(s) |
|---|---|---|
| **PACK 01**: Standalone Pharmacy | `docs/integrations/PACK_01_PHARMACY_INTEGRATION_GUIDE.md` | `examples/standalone-pharmacy` |
| **PACK 02**: Diagnostic Centers (Lab & Radiology) | `docs/integrations/PACK_02_DIAGNOSTICS_INTEGRATION_GUIDE.md` | `examples/diagnostic-laboratory`, `examples/radiology-center` |
| **PACK 03**: Pharmaceutical Manufacturing, QMS & Serialization | `docs/integrations/PACK_03_PHARMA_MANUFACTURING_INTEGRATION_GUIDE.md` | `examples/pharma-manufacturing` |
| **PACK 04**: Life Sciences, Biobanking, Genomics & Clinical Research | `docs/integrations/PACK_04_LIFE_SCIENCES_INTEGRATION_GUIDE.md` | `examples/life-sciences-lims`, `examples/biobank`, `examples/clinical-research` |
| **PACK 05**: Outpatient & Specialty EMR | `docs/integrations/PACK_05_OUTPATIENT_EMR_INTEGRATION_GUIDE.md` | `examples/outpatient-emr` |
| **PACK 06**: Longitudinal EHR | `docs/integrations/PACK_06_LONGITUDINAL_EHR_INTEGRATION_GUIDE.md` | `examples/longitudinal-ehr` |
| **PACK 07**: Composable Hospital HIS (5 Profiles) | `docs/integrations/PACK_07_COMPOSABLE_HIS_INTEGRATION_GUIDE.md` | `examples/hospital-his` |
| **PACK 08**: Regional & National HIE | `docs/integrations/PACK_08_REGIONAL_HIE_INTEGRATION_GUIDE.md` | `examples/regional-hie` |

## 3. Individual Universal Starter 20-Section Guides (`docs/integrations/modules/`)
- `docs/integrations/modules/healthcore-starter-pharmacy-commerce.md`
- `docs/integrations/modules/healthcore-starter-diagnostics-core.md`
- `docs/integrations/modules/healthcore-starter-pharma-manufacturing.md`
- `docs/integrations/modules/healthcore-starter-pharma-qms.md`
- `docs/integrations/modules/healthcore-starter-pharma-serialization.md`
- `docs/integrations/modules/healthcore-starter-life-sciences-lims.md`
- `docs/integrations/modules/healthcore-starter-biobanking.md`
- `docs/integrations/modules/healthcore-starter-genomics-research.md`
- `docs/integrations/modules/healthcore-starter-clinical-research.md`

## 4. Per-Module READMEs
Every one of the 58 modules in the Maven reactor contains a dedicated `README.md` at `<module>/README.md` documenting its Maven coordinate, dependencies, Liquibase changelog, REST base path, and SPI extension points.
