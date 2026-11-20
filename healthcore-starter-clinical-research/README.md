# `healthcore-starter-clinical-research`

## Overview
Independent clinical trials & Electronic Data Capture (EDC) starter providing technical structures aligned with ICH E6(R3) Good Clinical Practice and CDISC (`CDASH`/`SDTM`/`ADaM`) data models.

- **Maven Coordinate**: `com.opeshealth.healthcore:healthcore-starter-clinical-research:1.0.0`
- **Dependencies**: `healthcore-persistence-core`, `healthcore-security-audit` (**Zero clinical EMR, MPI, or HIS dependencies**)
- **Liquibase Changelog**: `classpath:db/changelog/clinical_research/001-clinical-research.xml`
- **REST Base Path**: `/api/v1/lifesciences/clinical-research`

## Key Capabilities & Workflows
1. Pseudonymous subject screening & enrollment gated by eligibility and documented `eConsent` version.
2. SDTM-tagged eCRF visit capture with data query lifecycle (`QUERIED` -> `SDV_VERIFIED` -> PI-signed `LOCKED` only when zero open queries remain).
3. AE/SAE safety event capture with automatic expedited reporting flag for serious, related events.
4. De-identified CDISC SDTM `DM` JSON export (`/protocols/{protocolCode}/export/cdisc-sdtm`).
