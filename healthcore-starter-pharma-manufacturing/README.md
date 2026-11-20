# `healthcore-starter-pharma-manufacturing`

## Overview
Independent Spring Boot 4 starter for pharmaceutical manufacturing operations aligned with WHO GMP, ICH Q7–Q10, EU GMP Annex 11, and FDA 21 CFR Part 11 technical controls (not a substitute for independent regulatory validation/GMP inspection).

- **Maven Coordinate**: `com.opeshealth.healthcore:healthcore-starter-pharma-manufacturing:1.0.0`
- **Dependencies**: `healthcore-persistence-core`, `healthcore-security-audit` (**Zero MPI, EMR, LIS, or HIS dependencies**)
- **Liquibase Changelog**: `classpath:db/changelog/pharma_manufacturing/001-pharma-manufacturing.xml`
- **REST Base Path**: `/api/v1/pharma/manufacturing`

## Key Capabilities & Workflows
1. Product master & versioned Master Formula / BOM with maker-checker electronic approval (`ElectronicSignatureSpi`).
2. Electronic Batch Manufacturing Record (`eBMR`) lifecycle: mandatory pre-production line clearance gate, dual-witness raw-material weighing, in-process controls (IPC), yield reconciliation, and QA disposition (`RELEASED`, `QUARANTINE`, `REJECTED`).
3. Forward/backward batch genealogy linking raw material lots to finished batches.

## Extension Points
- `PharmaManufacturingService.PharmaBatchReleasePolicySpi` — pluggable QA/QP release policy rules.
- `ElectronicSignatureSpi` — pluggable PKI/HSM/OIDC step-up signature verification.

## Quick Install
```xml
<dependency>
    <groupId>com.opeshealth.healthcore</groupId>
    <artifactId>healthcore-starter-pharma-manufacturing</artifactId>
</dependency>
```
