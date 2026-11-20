# `healthcore-starter-genomics-research`

## Overview
Independent genomics and bioinformatics research metadata starter tracking library prep, sequencing runs, Q30 QC thresholds, pipeline/reference-genome provenance, and governed dataset release.

- **Maven Coordinate**: `com.opeshealth.healthcore:healthcore-starter-genomics-research:1.0.0`
- **Dependencies**: `healthcore-persistence-core`, `healthcore-security-audit` (**Zero clinical EMR, MPI, or HIS dependencies**)
- **Liquibase Changelog**: `classpath:db/changelog/genomics_research/001-genomics-research.xml`
- **REST Base Path**: `/api/v1/lifesciences/genomics`

## Extension Points
- `GenomicsResearchService.VariantStoreAdapterSpi` — integrate GA4GH `htsget`, OpenCGA, Hail, or object-storage VCF integrity verifiers.
