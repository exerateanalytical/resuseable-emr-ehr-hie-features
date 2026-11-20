# External Applications & Multi-Language Integration Guide

OPES HealthCore is **headless-first and API-first** (`Pure Java 25 + Spring Boot 4.0` backend SDK). External applications in any language can consume HealthCore packs cleanly over OpenAPI 3.1 REST, HL7 FHIR R4, HL7 v2, ASTM, DICOMweb, and signed webhooks—without adopting any opinionated UI.

---

## 1. Existing Java / Spring Boot Application
Import `healthcore-bom` and add ONLY the single starter or industry pack you need:
```xml
<dependency>
    <groupId>com.opeshealth.healthcore</groupId>
    <artifactId>healthcore-pack-pharmacy</artifactId>
</dependency>
```
Spring Boot 4 auto-configuration registers JPA entities, repositories, services, and REST controllers automatically. Override behavior cleanly via Spring `@Bean` implementations of HealthCore SPIs (`ElectronicSignatureSpi`, `PharmacyDispensingPolicySpi`, `PharmaBatchReleasePolicySpi`, `DiagnosticsQcPolicySpi`, `VariantStoreAdapterSpi`).

## 2. Python / Data Science / FastAPI / Bioinformatics Pipelines
- Generate typed clients directly from `/v3/api-docs` (`openapi-python-client`).
- Pass `Authorization: Bearer <token>` and `X-Facility-Id: <uuid>`.
- Example (Biobanking / Genomics Python pipeline cataloging a verified VCF artifact):
```python
import requests
resp = requests.post(
    "https://api.example.org/api/v1/lifesciences/genomics/datasets",
    headers={"Authorization": f"Bearer {TOKEN}", "X-Facility-Id": FACILITY_ID},
    json={
        "sequencingRunId": run_id,
        "datasetAccession": "EGAD-2026-01",
        "pipelineNameVersion": "nf-core/sarek@3.4.2",
        "referenceGenome": "GRCh38.p14",
        "vcfUri": "s3://bucket/sample01.vcf.gz",
        "vcfSha256": vcf_sha256,
        "accessTier": "CONTROLLED_DAC",
    },
    timeout=15,
)
resp.raise_for_status()
```

## 3. .NET / C# Enterprise & ERP Clients
- Generate strongly-typed clients with `NSwag` or `Microsoft.Kiota` from `/v3/api-docs`.
- Subscribe to signed HealthCore webhooks (`X-HealthCore-Signature` HMAC-SHA256) via `healthcore-starter-api-product` for real-time ERP inventory, billing ledger, or QA batch-release synchronization.

## 4. Web & JavaScript Frontends (React / Angular / Vue / HTMX)
- OPES HealthCore itself contains **zero TypeScript/Node build dependencies**; external frontend teams integrate solely via OpenAPI 3.1 JSON (`/v3/api-docs`) or FHIR R4 REST (`/fhir/r4/**`) with standard OIDC PKCE tokens and `Idempotency-Key` headers.

## 5. Kotlin / Android / iOS (Kotlin Multiplatform)
- Consume the companion `healthcore-mobile-kmp` module (`OfflineSyncEngine`, `HealthCoreApiClient`, `EncryptedLocalStore`) for offline-first clinic, pharmacy branch, CHW, or field research specimen collection with automatic vector-clock conflict resolution.

## 6. Medical Devices, Analyzers, PACS & ERP Systems
- **Lab Analyzers**: HL7 v2 ORU/ORM & ASTM E1381/E1394 via `healthcore-starter-hl7-astm-dicom`.
- **Radiology PACS / Modalities**: DICOMweb (`QIDO-RS`, `WADO-RS`, `STOW-RS`) & Modality Worklist via `healthcore-starter-radiology` + `healthcore-starter-hl7-astm-dicom`.
- **ERP / SAP / Odoo / DHIS2**: Transactional Outbox + Dead-Letter Queue replay via `healthcore-starter-integration-ops`.
- **Cold-Chain IoT Sensors**: MQTT ingestion via `healthcore-starter-biomedical-coldchain` and temperature excursion endpoints in `healthcore-starter-pharma-serialization` and `healthcore-starter-biobanking`.
