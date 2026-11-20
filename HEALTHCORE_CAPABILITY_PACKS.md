# OPES HealthCore — Eight Composable Industry Capability Packs

OPES HealthCore is architected as **8 composable Industry Capability Packs** built from independently installable Spring Boot 4 starters—NOT eight separate monoliths.

| Pack # | Maven Artifact | Target Industry | Pack Manifest | Pack Liquibase Master | Minimal Example(s) |
|---|---|---|---|---|---|
| **PACK 01** | `healthcore-pack-pharmacy` | Independent Retail, Community & Chain Pharmacies | `packs/healthcore-pack-pharmacy/.../pack-manifest.json` | `db/changelog/packs/pack-pharmacy-master.xml` | `examples/standalone-pharmacy` |
| **PACK 02** | `healthcore-pack-diagnostics` | Medical Laboratories, Radiology Centers & Combined Diagnostic Centers | `packs/healthcore-pack-diagnostics/.../pack-manifest.json` | `db/changelog/packs/pack-diagnostics-master.xml` | `examples/diagnostic-laboratory`, `examples/radiology-center` |
| **PACK 03** | `healthcore-pack-pharma-manufacturing` | Pharmaceutical Manufacturing, QC/QMS & GS1 Serialization/GDP | `packs/healthcore-pack-pharma-manufacturing/.../pack-manifest.json` | `db/changelog/packs/pack-pharma-manufacturing-master.xml` | `examples/pharma-manufacturing` |
| **PACK 04** | `healthcore-pack-life-sciences` | Research LIMS/ELN, ISO 20387 Biobanks, Genomics & Clinical Trials (EDC) | `packs/healthcore-pack-life-sciences/.../pack-manifest.json` | `db/changelog/packs/pack-life-sciences-master.xml` | `examples/life-sciences-lims`, `examples/biobank`, `examples/clinical-research` |
| **PACK 05** | `healthcore-pack-emr` | Outpatient Clinics, Physician Practices & Specialty Clinics | `packs/healthcore-pack-emr/.../pack-manifest.json` | `db/changelog/packs/pack-emr-master.xml` | `examples/outpatient-emr` |
| **PACK 06** | `healthcore-pack-ehr` | Multi-Site Longitudinal EHR & IPS Portability Custodians | `packs/healthcore-pack-ehr/.../pack-manifest.json` | `db/changelog/packs/pack-ehr-master.xml` | `examples/longitudinal-ehr` |
| **PACK 07** | `healthcore-pack-his` | Composable Hospital HIS (5 Profiles: Outpatient, General, Multispecialty, Tertiary, Multi-Facility Network) | `packs/healthcore-pack-his/.../pack-manifest.json` | `db/changelog/packs/pack-his-master.xml` | `examples/hospital-his` |
| **PACK 08** | `healthcore-pack-hie` | Regional & National Health Information Exchanges | `packs/healthcore-pack-hie/.../pack-manifest.json` | `db/changelog/packs/pack-hie-master.xml` | `examples/regional-hie` |

## Composing Individual Starters Without a Full Pack
Any developer may import `healthcore-bom` and install a single starter directly (for example, ONLY `healthcore-starter-biobanking` or ONLY `healthcore-starter-pharma-qms`) without pulling the rest of the pack. Examples `life-sciences-lims`, `biobank`, `clinical-research`, `diagnostic-laboratory`, and `radiology-center` explicitly demonstrate single-starter/subset composition.
