# API & Standards Conformance Report — Opes HealthCore

> **Conformance Honesty Statement:** This report documents implemented REST, FHIR R4, HL7 v2, IHE, and DICOMweb endpoints and their current verification state. No standards certification (IHE Connectathon, ONC Health IT, HL7 FHIR Inferno/Touchstone) is claimed until independent conformance testing passes in a live environment.

---

## 1. Core Domain REST APIs
| Domain Starter | Base Path | Authorization Policy | Tenant Scope |
| :--- | :--- | :--- | :--- |
| Tenant Governance | `/api/v1/governance/**` | `PERM_system:admin`, `PERM_facility:onboard`, `PERM_facility:manage` | Strict `FacilityContext` + `TenantGovernanceGuardFilter` |
| Scheduling | `/api/v1/scheduling/**` | `PERM_patient:read`, `PERM_patient:write` | Mandatory `facilityId` predicates |
| Unified Orders & Results | `/api/v1/orders-results/**` | `PERM_order:sign`, `PERM_lab:accession`, `PERM_lab:result:enter`, `PERM_lab:verify` | Mandatory `facilityId` predicates |
| Patient & Proxy Portal | `/api/v1/portal/**` | `PERM_self:record:read`, `PERM_self:appointment:request`, `PERM_facility:manage` | Strict patient/proxy ownership + `facilityId` |
| Radiology RIS | `/api/v1/ris/**` | `PERM_order:sign`, `PERM_dicom:index`, `PERM_imaging:report:sign`, `PERM_patient:read` | Mandatory `facilityId` predicates |
| Emergency & Inpatient Nursing | `/api/v1/emergency-nursing/**` | `PERM_patient:read`, `PERM_patient:write`, `PERM_encounter:sign`, `PERM_facility:manage` | Mandatory `facilityId` predicates |
| Hospital Analytics & CQM | `/api/v1/analytics/**` | `PERM_facility:manage`, `PERM_audit:read`, `PERM_system:admin` | Mandatory `facilityId` SQL + repo predicates |
| Reference Hospital UI | `/ui/**` | Isolated `@Order(1)` Spring MVC/Thymeleaf security chain | Active facility session |

## 2. Healthcare Standards Surface & Current Conformance Boundaries
- **HL7 FHIR R4:**
  - `SchedulingController` exposes FHIR R4 `Appointment` and `Schedule` projections; `UnifiedOrdersResultsController` exposes FHIR R4 `ServiceRequest` and `DiagnosticReport` projections.
  - `HieInteropService` enforces strict `PERM_patient:read` / write permissions (zero `isAuthenticated()` fallbacks) and tenant facility isolation (zero `findAll()`).
  - **Remaining Conformance Requirement:** Full automated HAPI FHIR StructureDefinition & IPS profile validation against live test fixtures must be executed in JDK 25 CI (`mvn clean verify`).
- **HL7 v2.5.1:**
  - `HieInteropService` enforces tenant-scoped `MSH-10` idempotency (`hc_inbound_idempotency`) and durably maps and persists:
    - `ADT^A01`, `ADT^A04`, `ADT^A08` → `hc_patients` (MPI Golden Record)
    - `ORM^O01` (`NW`/`CA`/`XO`/`HD`/`DC`) → `hc_hl7_orders`
    - `ORU^R01` (`OBX` observations & abnormal flags) → `hc_hl7_results`
    - `SIU^S12`, `SIU^S13`, `SIU^S14`, `SIU^S15`, `SIU^S26` → `hc_hl7_appointments`
    - `DFT^P03` (`CG` charges & `CD`/`AJ` credit reversals) → `hc_hl7_financial_txns`
  - Any unsupported message type (e.g., `MDM^T02`) is explicitly rejected with `ackCode="AR"` and quarantined to `hc_hie_dlq_messages`; malformed payloads return `ackCode="AE"`.
  - **Limitation:** Partner-specific Z-segments and custom site interface mappings must still be verified against live partner HL7 fixtures before cutover.
- **DICOMweb (STOW-RS / WADO-RS / QIDO-RS):**
  - `DicomPacsProxyService` (`/api/v1/hie/dicom/pacs/**`) validates DICOM UID format (max 64 chars, numeric dot-separated, no leading zeros), requires non-empty binary payloads, enforces tenant ownership on STOW/WADO/QIDO, fails closed with `501 NOT IMPLEMENTED` when `healthcore.hie.dicom.pacs-enabled=false` (never fakes STOW success), and quarantines genuine upstream PACS outages to `hc_hie_dlq_messages`.
  - **Remaining Conformance Requirement:** Integration test against a live Orthanc or DCM4CHEE PACS container is required prior to claiming full production PACS conformance.
- **IHE mCSD / MHD / PDQm:**
  - Backed by persisted tenant-scoped facility/document repositories rather than hardcoded sample entries.
