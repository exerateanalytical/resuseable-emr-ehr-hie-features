# `healthcore-starter-pharma-serialization`

## Overview
Independent pharmaceutical serialization, warehouse lot control, cold-chain excursion hold, and distribution traceability starter aligned with GS1 Application Identifiers (`(01)GTIN(17)YYMMDD(10)LOT(21)SERIAL`) and 18-digit SSCC aggregation.

- **Maven Coordinate**: `com.opeshealth.healthcore:healthcore-starter-pharma-serialization:1.0.0`
- **Dependencies**: `healthcore-persistence-core`, `healthcore-security-audit` (**Zero MPI, EMR, LIS, or HIS dependencies**)
- **Liquibase Changelog**: `classpath:db/changelog/pharma_serialization/001-pharma-serialization.xml`
- **REST Base Path**: `/api/v1/pharma/serialization`

## Key Capabilities & Workflows
1. GS1 unit commissioning (`TraceableLotPrimitive`) and SSCC case/pallet aggregation.
2. Warehouse lot quarantine, cold-chain temperature excursion detection (`EXCURSION_HOLD`), and QA electronic release.
3. Batch-wide recall stopping dispatches and marking all serialized units `RECALLED`.

## Extension Points
- `PharmaSerializationService.PharmaSerializationProviderSpi` — integrate national serialization hubs (EU FMD, US DSCSA, GS1 EPCIS).
- `ElectronicSignatureSpi` — QA distribution release e-signature provider.
