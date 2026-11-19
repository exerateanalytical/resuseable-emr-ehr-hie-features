# Incident Response Plan — Opes HealthCore HIS Platform

## 1. Severity Classification
| Severity | Definition | Target Initial Response | Examples |
| :--- | :--- | :--- | :--- |
| **SEV-1 (Critical / Patient Safety or PHI Breach)** | Active cross-tenant data exposure, clinical safety failure, medication/lab corruption, or total hospital EHR outage. | Immediate (< 15 min) | Confirmed cross-facility PHI leak; unacknowledged critical lab pipeline down; FEFO inventory corruption; ransomware/DB breach. |
| **SEV-2 (High)** | Single department workflow degraded with clinical workaround available. | < 30 min | PACS binary proxy unreachable (DLQ accumulating); OIDC token latency spikes; Billing clearinghouse offline. |
| **SEV-3 (Medium)** | Non-urgent reporting, analytics, or portal degradation. | < 4 hours | Analytics KPI timeout; non-critical template PDF failure. |

## 2. Containment Playbooks
### A. Suspected Cross-Tenant or Unauthorized PHI Access
1. **Isolate Tenant Immediately:** Set affected facility to `SUSPENDED` via `/api/v1/governance/facilities/{id}/lifecycle` (`TenantGovernanceGuardFilter` immediately returns `403`).
2. **Revoke Credentials:** Revoke compromised OIDC sessions/clients in Keycloak and rotate affected API/webhook secrets.
3. **Preserve Evidence:** Export tamper-evident audit logs (`ClinicalAuditService` and `ClinicalAuditLogger` SHA-256 hash chain) before any remediation restart.
4. **Verify Integrity:** Run hash-chain verification over the affected facility's audit ledger to detect tampering or gaps.

### B. Clinical Safety or AI Governance Anomaly
1. **Engage Kill-Switch:** Disable Clinical AI for the affected facility (or globally) via the durable tenant AI policy flag and/or facility feature flag kill-switch.
2. **Quarantine Affected Records:** Identify any `DRAFT_PENDING_ATTESTATION` or recently attested records in `hc_clinical_ai_drafts` and notify the Chief Medical Information Officer (CMIO) / Clinical Safety Officer.
3. **Mandatory Audit Outage:** Because `ClinicalAuditLogger` fails closed (`ClinicalAuditPersistenceException`), restore database connectivity to audit storage before resuming AI operations; never disable audit persistence to restore throughput.

### C. Database or PACS Outage
1. Verify read-only clinical continuity / downtime paper procedures with ward nursing leadership.
2. Monitor `hc_hie_dlq_messages` for quarantined DICOM/HL7 payloads during PACS outage.
3. Execute documented backup/restore or failover drill (`docs/PRODUCTION_RUNBOOK.md`).

## 3. Post-Incident Review & Regulatory Notification
- Produce a blameless root-cause analysis within 72 hours, attach regression tests closing the failure mode, and update `docs/KNOWN_LIMITATIONS_REGISTER.md` and `docs/CLINICAL_SAFETY_CASE.md`.
- Breach notifications (e.g., HIPAA 60-day / GDPR 72-hour / local Ministry of Health rules) are executed by the deploying organization's Data Protection Officer and Legal Counsel.
