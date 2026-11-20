# `healthcore-starter-pharmacy-commerce`

## Overview
Truly standalone retail, community, and chain pharmacy starter designed for independent pharmacies and e-pharmacy APIs without requiring EMR, LIS, inpatient, or HIS modules.

- **Maven Coordinate**: `com.opeshealth.healthcore:healthcore-starter-pharmacy-commerce:1.0.0`
- **Dependencies**: `healthcore-persistence-core`, `healthcore-security-audit`, `healthcore-starter-terminology-cdss` (**Zero EMR, LIS, MPI, inpatient, or HIS dependencies**)
- **Liquibase Changelog**: `classpath:db/changelog/pharmacy_commerce/001-pharmacy-commerce.xml`
- **REST Base Path**: `/api/v1/pharmacy-commerce`

## Key Capabilities & Workflows
1. Drug product catalog (generic/brand/strength/formulation/ATC/RxNorm/controlled-substance flag).
2. Supplier goods receipt with GS1 GTIN & expiry validation (`TraceableLotPrimitive`).
3. FEFO lot selection & prescription dispensing with controlled-substance dual-witness enforcement.
4. Batch recall quarantine (`QUARANTINED_RECALL`) and near-expiry alerts.

## Extension Points
- `PharmacyCommerceService.PharmacyDispensingPolicySpi` — pluggable country-specific dispensing, generic substitution, or insurance copay rules.
