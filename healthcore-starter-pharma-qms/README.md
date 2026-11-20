# `healthcore-starter-pharma-qms`

## Overview
Independent pharmaceutical Quality Management System (QMS) & Quality Control (QC) starter providing technical controls aligned with WHO GMP, ICH Q10, EU GMP Annex 11, and FDA 21 CFR Part 11 (requires organizational QMS validation for compliance).

- **Maven Coordinate**: `com.opeshealth.healthcore:healthcore-starter-pharma-qms:1.0.0`
- **Dependencies**: `healthcore-persistence-core`, `healthcore-security-audit` (**Zero MPI, EMR, LIS, or HIS dependencies**)
- **Liquibase Changelog**: `classpath:db/changelog/pharma_qms/001-pharma-qms.xml`
- **REST Base Path**: `/api/v1/pharma/qms`

## Key Capabilities & Workflows
1. Controlled SOP & specification versioning with SHA-256 content digest and maker-checker electronic approval.
2. QC sample testing with automatic Out-of-Specification (`OOS_HOLD`) block preventing CoA issuance until resolved.
3. Certificate of Analysis (`CoA`) electronic release signatures.
4. Deviations, CAPA, change control, and recalls requiring root cause, CAPA plan, and effectiveness verification on `CRITICAL` records.

## Extension Points
- `ElectronicSignatureSpi` — pluggable electronic signature verifier.
