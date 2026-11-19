# Data Dictionary — Opes HealthCore Multi-RDBMS Schema

All tables are owned exclusively by **Liquibase** via `healthcore-quickstart-host/src/main/resources/db/changelog/db.changelog-master.xml` (34 changelog includes) using portable cross-DB type aliases across **PostgreSQL 16+**, **Microsoft SQL Server 2022+**, and **MySQL 8.4+**.

| Portable Alias | PostgreSQL 16+ | SQL Server 2022+ | MySQL 8.4+ |
| :--- | :--- | :--- | :--- |
| `${uuid_type}` | `UUID` | `UNIQUEIDENTIFIER` | `BINARY(16)` |
| `${ts_type}` | `TIMESTAMPTZ` | `DATETIMEOFFSET` | `DATETIME(6)` |
| `${clob_type}` | `TEXT` | `NVARCHAR(MAX)` | `LONGTEXT` |
| `${bool_type}` | `BOOLEAN` | `BIT` | `TINYINT(1)` |

---

## Core Platform & Governance Tables
- **Persistence & Security (`core`, `security`):** Outbox events, inbound idempotency keys (`hc_inbound_idempotency`), tamper-evident clinical audit log, patient consent records (including AI-assisted processing consent flags).
- **Tenant Governance (`tenant_governance`):**
  - `hc_tenant_organizations` — Top-level health networks/ministries.
  - `hc_tenant_facilities` — Hospitals/clinics with `lifecycle_status` (`ACTIVE`, `READ_ONLY`, `SUSPENDED`).
  - `hc_facility_departments` — Wards, labs, imaging, and outpatient clinics.
  - `hc_facility_feature_flags` — Per-facility kill switches & rollout flags.
  - `hc_cross_facility_grants` — Time-bound cross-facility referral/access authorizations.

## Patient Administration & Clinical Core Tables
- **MPI (`mpi`):** Encrypted patient identities, blind-index lookup tokens, golden record links, merge/unmerge audit lineage.
- **Scheduling (`scheduling`):** `hc_sched_resource_calendars`, `hc_sched_appointment_types`, `hc_sched_appointments`, `hc_sched_waitlist`.
- **EMR Clinical (`emr`):** Encounters, structured SOAP notes, observations, allergies, problem lists, eMAR administrations, ward transfer logs.
- **Unified Orders & Results (`orders_results`):** `hc_order_sets`, `hc_unified_orders`, `hc_order_specimens`, `hc_clinical_results`, `hc_reflex_order_rules`.
- **Patient Portal (`patient_portal`):** `hc_portal_accounts`, `hc_portal_proxy_grants`, `hc_portal_message_threads`, `hc_portal_messages`.

## Hospital Operations & Specialty Tables
- **Pharmacy & LIS (`pharmacy_lis`):** Formulary items, FEFO stock lots, dispensing transactions, lab panels, Westgard QC runs.
- **Hospital Ops (`hospital_ops`):** `hc_ward_beds`, `hc_triage_assessments`, `hc_fluid_balance`, `hc_shift_handovers`, `hc_blood_donors`, `hc_blood_units`, `hc_surgical_schedules`, cold-chain telemetry, biomedical maintenance, CRVS/mortuary, workforce credentials & rosters.
- **Radiology RIS (`radiology`):**
  - `hc_ris_modality_worklist` — DICOM MWL orders, accession numbers, `study_instance_uid`, MPPS status, STAT priority.
  - `hc_ris_diagnostic_reports` — Signed structured radiology reports, immutable addenda (`supersedes_report_id`), closed-loop critical finding acknowledgment & verbal read-back.
- **Emergency & Inpatient Nursing (`emergency_nursing`):**
  - `hc_ed_visits` — ESI 1–5 emergency triage board, arrival mode, bay assignment, disposition.
  - `hc_nursing_assignments` — Nurse-to-patient ward/bed shift assignments with ratio caps.
  - `hc_nursing_news2_scores` — NEWS2 vital parameters, computed score, risk band, and escalation flags.
  - `hc_discharge_readiness` — 5-gate multi-disciplinary discharge checklist per encounter.

## Revenue Cycle, Interoperability, AI & Analytics Tables
- **UHC & Billing (`uhc_billing`):** Tariffs, patient accounts, balanced double-entry journal headers/lines, CBHI policies, UHC vouchers, insurance claims, payment transactions.
- **HIE Interop (`hie`):** `hc_dicom_studies`, `hc_ihe_documents`, HL7 deduplication ledger, `hc_hie_dlq_messages`.
- **Public Health (`public_health`):** Notifiable disease cascades, immunization registries, DHIS2 sync queues.
- **Clinical AI Governance (`clinical_ai`):** Durable AI draft quarantine table, tenant AI policy kill-switch table, hash-chained `ClinicalAuditLogger` ledger.
- **Analytics & Quality (`analytics`):**
  - `hc_analytics_quality_measures` — CQM numerators, denominators, exclusions, compliance rates, and data lineage.
  - `hc_analytics_cohort_exports` — Governed cohort export requests, k-anonymity thresholds, 4-eyes approval metadata, and SHA-256 lineage digests.
