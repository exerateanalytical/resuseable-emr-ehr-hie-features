# `healthcore-starter-life-sciences-lims`

## Overview
Independent life-sciences Research LIMS and Electronic Laboratory Notebook (ELN) starter built specifically for scientific research labs, biotech R&D, and academic core facilities. **Scientific samples are modeled independently from clinical patients.**

- **Maven Coordinate**: `com.opeshealth.healthcore:healthcore-starter-life-sciences-lims:1.0.0`
- **Dependencies**: `healthcore-persistence-core`, `healthcore-security-audit` (**Zero MPI, EMR, or HIS dependencies**)
- **Liquibase Changelog**: `classpath:db/changelog/life_sciences_lims/001-life-sciences-lims.xml`
- **REST Base Path**: `/api/v1/lifesciences/lims`

## Key Capabilities & Workflows
1. Research studies and versioned protocols gated by Ethics/IRB approval before sample intake.
2. Domain-neutral scientific sample registry (`DONOR_PSEUDONYM`, `ORGANISM`, `CELL_LINE`, `TISSUE_CULTURE`, `ENVIRONMENTAL`) with chain-of-custody transfers.
3. Versioned ELN experiments with maker-checker electronic review signatures (`SIGNED_LOCKED` immutability).

## Extension Points
- `LifeSciencesLimsService.ResearchInstrumentAdapterSpi` — raw assay parser for PCR, ELISA, LC-MS, and flow cytometers.
- `ElectronicSignatureSpi` — scientific review signature provider.
