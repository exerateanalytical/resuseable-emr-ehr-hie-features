# Pack 07 Integration Guide — Composable Hospital Information System (`healthcore-pack-his`)

1. **Purpose & Scope**: Composable Hospital Information System supporting 5 distinct hospital tiers without forcing unnecessary modules onto smaller hospitals.
2. **Five Selectable Hospital Profiles**:
   - `outpatient-hospital`: Base HIS + Revenue Coding & Claims
   - `general-hospital`: + Radiology RIS, Revenue Claims, Workforce Roster
   - `multispecialty`: + Radiology, Surgery/OT, Blood Bank, Revenue Claims
   - `tertiary-referral`: + Radiology, Surgery/OT, Blood Bank, Biomedical/Cold-Chain, CRVS/Mortuary, Analytics, Revenue Claims
   - `multi-facility-network`: + Tenant Governance, Offline Sync, Integration Ops, Analytics
3. **Included vs Excluded Modules**: Base pack composes `healthcore-pack-ehr`, `hospital-ops`, `emergency-nursing`, `pharmacy-lis`, `uhc-billing`. Strictly excludes pharmaceutical manufacturing (`pharma-*`) and life-sciences (`life-sciences-lims`, `biobanking`, `genomics-research`).
4. **Prerequisites**: Java 25, Spring Boot 4.0, Maven 3.9+, PostgreSQL 16+ / SQL Server 2022 / MySQL 8.4+.
5. **Maven Setup**: Import `healthcore-bom:1.0.0`, add `healthcore-pack-his`, and select only the additional hospital starters required by your facility profile.
6. **Database & Liquibase Setup**: `spring.liquibase.change-log: classpath:db/changelog/packs/pack-his-master.xml` (plus any profile-specific starter changelogs).
7. **Required Environment Variables**: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `HEALTHCORE_OIDC_ISSUER_URI`, `HEALTHCORE_STORAGE_DIR`.
8. **Security & Multi-Facility Governance**: OIDC JWT + `X-Facility-Id`, Hibernate `facilityFilter`, RBAC per clinical/operations role, break-glass auditing, and optional `tenant-governance` tier quotas.
9. **Core REST Endpoints**: `/api/v1/hospital-ops/**`, `/api/v1/emergency-nursing/**`, `/api/v1/pharmacy/**`, `/api/v1/lis/**`, `/api/v1/billing/**`, plus optional `/api/v1/radiology/**`, `/api/v1/surgery/**`, `/api/v1/blood-bank/**`, `/api/v1/revenue/**`.
10. **End-to-End Sample Workflow**: ED triage -> inpatient ADT bed admission -> nursing MAR & eMAR -> lab/radiology orders -> pharmacy dispense -> discharge summary -> ICD/CPT coding, UHC/CBHI claim submission & double-entry ledger reconciliation.
11. **Java Service Injection**: Inject `HospitalOpsService`, `EmergencyNursingService`, `UhcBillingService`, `RevenueCodingClaimsService`, etc.
12. **Extension Points (SPIs)**: `PaymentGatewaySpi`, `PayerClaimAdapterSpi`, `WorkforceCredentialSpi`, `ClinicalDecisionSupportSpi`.
13. **Reference Web UI Host**: `healthcore-quickstart-host` provides 17 Pure Java Thymeleaf operational consoles demonstrating full-hospital orchestration (kept strictly outside headless starters).
14. **Offline & Disaster Recovery**: Combine `healthcore-starter-offline-sync` with `scripts/backup-postgres.sh` and `scripts/restore-drill-verify.sh`.
15. **Standards Alignment**: HL7 FHIR R4, HL7 v2, DICOMweb, IHE XDS.b/PIX/PDQ, WHO ICD-10/ICD-11, LOINC, RxNorm, DHIS2 public-health reporting.
16. **Minimal Working Example**: `examples/hospital-his` (port `8090`).
17. **Smoke Verification Commands**: `cd examples/hospital-his && docker compose up -d && mvn spring-boot:run`.
18. **Testing Strategy**: Full 51-suite JUnit regression + `MultiDbTestcontainersIT` (PostgreSQL 16, MSSQL 2022, MySQL 8.4) + `CapabilityPackIsolationTest`.
19. **Common Pitfalls & Troubleshooting**: Never use `ddl-auto: update` in production; keep `ddl-auto: validate` with Liquibase as sole schema authority.
20. **Upgrade & Compatibility Notes**: Start with `outpatient-hospital` and add modules (`radiology`, `surgery-ot`, `blood-bank`) incrementally with zero destructive migrations.
