# OPES HealthCore — Public API Catalog

All REST endpoints require OIDC JWT authentication (`Authorization: Bearer <jwt>`), explicit RBAC authority checks (`@PreAuthorize`), and tenant context (`X-Facility-Id` header) unless explicitly designated public health/OpenAPI metadata. OpenAPI 3.1 specifications are served dynamically at `/v3/api-docs`.

| Module / Pack | Base Path | Key Endpoints |
|---|---|---|
| `healthcore-starter-pharmacy-commerce` (Pack 01) | `/api/v1/pharmacy-commerce` | `POST /products`, `POST /goods-receipt`, `POST /dispense`, `POST /recall/{batchNumber}`, `GET /alerts/near-expiry` |
| `healthcore-starter-diagnostics-core` (Pack 02) | `/api/v1/diagnostics-core` | `POST /referring-partners`, `POST /orders`, `POST /orders/{id}/reject`, `POST /orders/{id}/verify-release`, `POST /quality-incidents` |
| `healthcore-starter-radiology` (Pack 02 / 07) | `/api/v1/radiology` | Imaging order intake, modality worklist, DICOMweb study metadata, structured radiology report sign-off |
| `healthcore-starter-pharma-manufacturing` (Pack 03) | `/api/v1/pharma/manufacturing` | `POST /products`, `POST /formulas`, `POST /formulas/{id}/approve`, `GET/POST /batches`, `POST /batches/{id}/line-clearance`, `POST /batches/{id}/dispense`, `POST /batches/{id}/ipc`, `POST /batches/{id}/disposition`, `GET /batches/{id}/genealogy` |
| `healthcore-starter-pharma-qms` (Pack 03) | `/api/v1/pharma/qms` | `GET/POST /documents`, `POST /documents/{id}/approve`, `GET/POST /qc`, `POST /qc/{id}/issue-coa`, `GET/POST /capa`, `POST /capa/{id}/close` |
| `healthcore-starter-pharma-serialization` (Pack 03) | `/api/v1/pharma/serialization` | `POST /units/commission`, `POST /units/{batchNumber}/aggregate`, `GET/POST /warehouse-lots`, `POST /warehouse-lots/{id}/temperature`, `POST /warehouse-lots/{id}/release`, `POST /recall/{batchNumber}`, `GET /shipments`, `POST /shipments/dispatch` |
| `healthcore-starter-life-sciences-lims` (Pack 04) | `/api/v1/lifesciences/lims` | `GET/POST /studies`, `POST /studies/{id}/approve-ethics`, `POST /samples`, `POST /samples/{id}/transfer`, `POST /eln`, `POST /eln/{id}/sign` |
| `healthcore-starter-biobanking` (Pack 04) | `/api/v1/lifesciences/biobank` | `GET/POST /specimens`, `POST /specimens/aliquot`, `POST /freezers/{freezerId}/excursion`, `POST /donors/{donorPseudonym}/withdraw-consent`, `POST /mta/fulfill`, `POST /specimens/{id}/dispose` |
| `healthcore-starter-genomics-research` (Pack 04) | `/api/v1/lifesciences/genomics` | `POST /runs`, `GET/POST /datasets`, `POST /datasets/{id}/release` |
| `healthcore-starter-clinical-research` (Pack 04) | `/api/v1/lifesciences/clinical-research` | `POST /subjects/enroll`, `POST /ecrf`, `POST /ecrf/{id}/lock`, `POST /safety-events`, `GET /protocols/{protocolCode}/export/cdisc-sdtm` |
| EMR / EHR Core (Packs 05–07) | `/api/v1/{patients,scheduling,encounters,orders-results,workflows,portal,templates}` | Full ambulatory, longitudinal, and patient-portal CRUD & sign-off APIs |
| Hospital Operations & Revenue (Pack 07) | `/api/v1/{hospital-ops,emergency-nursing,surgery,blood-bank,billing,revenue}` | Ward/bed ADT, ED triage, MAR, OT checklists, blood crossmatch, UHC/CBHI billing, claims & coding |
| Interoperability & HIE (Packs 06–08) | `/fhir/r4/**`, `/api/v1/{ihe,hl7,integration-ops,developer-portal}` | HL7 FHIR R4, IPS `$summary`, HL7 v2, IHE PIX/PDQ/XDS.b, DLQ replay, API keys & webhooks |
