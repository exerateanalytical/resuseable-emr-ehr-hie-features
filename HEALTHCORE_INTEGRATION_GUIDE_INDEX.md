# OPES HealthCore — Developer Integration Guide Index

## 1. Industry Capability Pack Integration Guides (20-Section Standard Format)
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

## 2. External Multi-Language & Device Integration Guide
- `docs/integrations/EXTERNAL_APPLICATIONS_INTEGRATION_GUIDE.md` — Covers Java/Spring Boot embedding, Python/FastAPI/bioinformatics clients, .NET/ERP clients, external web frontends via OpenAPI 3.1, Kotlin Multiplatform/Android (`healthcore-kmp-client`), lab analyzers (HL7 v2/ASTM), PACS (DICOMweb), and cold-chain IoT/ERP bridges.

## 3. Per-Module READMEs
Every starter module in the reactor contains a dedicated `README.md` at `<module>/README.md` documenting its Maven coordinate, dependencies, Liquibase changelog, REST base path, and SPI extension points.
