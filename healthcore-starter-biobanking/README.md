# `healthcore-starter-biobanking`

## Overview
Independent biobanking and biospecimen repository starter providing technical capabilities aligned with ISO 20387 (General requirements for biobanking). Does not claim accreditation; requires institutional ISO 20387 QMS implementation.

- **Maven Coordinate**: `com.opeshealth.healthcore:healthcore-starter-biobanking:1.0.0`
- **Dependencies**: `healthcore-persistence-core`, `healthcore-security-audit` (**Zero clinical EMR, MPI, or HIS dependencies**)
- **Liquibase Changelog**: `classpath:db/changelog/biobanking/001-biobanking.xml`
- **REST Base Path**: `/api/v1/lifesciences/biobank`

## Key Capabilities & Workflows
1. Pseudonymous donor consent verification at intake and cascading donor consent withdrawal (`CONSENT_WITHDRAWN`).
2. Freezer/rack/box/slot location mapping, freeze-thaw cycle counter, and parent-child aliquot genealogy.
3. Cryogenic freezer excursion quarantine (`QUARANTINED_EXCURSION`).
4. Material Transfer Agreement (`MTA`) fulfillment with ethics gate and director e-signature, plus audited disposal.
