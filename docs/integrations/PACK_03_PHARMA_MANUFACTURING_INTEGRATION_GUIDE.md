# Pack 03 Integration Guide — Pharmaceutical Manufacturing, QMS & Serialization (`healthcore-pack-pharma-manufacturing`)

1. **Purpose & Scope**: Pharmaceutical manufacturers, sterile/non-sterile packaging plants, QC labs, and GDP cold-chain depots. Provides technical capabilities aligned with WHO GMP, ICH Q7–Q10, EU GMP Annex 11, FDA 21 CFR Part 11, and GS1 — organizational GMP validation and regulatory inspection remain the operator's responsibility.
2. **Target Use Cases**: Master formulas/BOMs, eBMR/eBPR batch execution, line clearance, dual-witness weighing, IPC, yield reconciliation, QC testing/OOS/CoA, SOP control, CAPA effectiveness verification, GS1 unit serialization, SSCC aggregation, cold-chain excursion holds, and recalls.
3. **Included vs Excluded Modules**: Includes `healthcore-starter-pharma-manufacturing`, `healthcore-starter-pharma-qms`, `healthcore-starter-pharma-serialization` (+ `persistence-core`, `security-audit`). Strictly excludes MPI, EMR, LIS, hospital ops, inpatient, surgery, and mortuary.
4. **Prerequisites**: Java 25, Spring Boot 4.0, Maven 3.9+, PostgreSQL 16+ / SQL Server 2022 / MySQL 8.4+.
5. **Maven Setup**: Import `healthcore-bom:1.0.0` and add `com.opeshealth.healthcore:healthcore-pack-pharma-manufacturing` (or install any individual pharma starter standalone).
6. **Database & Liquibase Setup**: `spring.liquibase.change-log: classpath:db/changelog/packs/pack-pharma-manufacturing-master.xml`.
7. **Required Environment Variables**: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `HEALTHCORE_OIDC_ISSUER_URI`.
8. **Security & Electronic Signatures**: Every maker-checker gate (`approveFormula`, `releaseBatch`, `approveControlledDocument`, `issueCertificateOfAnalysis`, `closeDeviationOrCapa`) enforces distinct creator vs signer identities and SHA-256 payload digest binding via `ElectronicSignatureSpi`.
9. **Core REST Endpoints**:
   - Manufacturing: `/api/v1/pharma/manufacturing/{products,formulas,batches}`
   - QMS/QC: `/api/v1/pharma/qms/{documents,qc,capa}`
   - Serialization/GDP: `/api/v1/pharma/serialization/{units/commission,warehouse-lots,recall,shipments/dispatch}`
10. **End-to-End Sample Workflow**: Approve Master Formula -> open eBMR -> verify line clearance -> dual-witness dispense raw materials -> record IPC -> QC CoA release -> QA batch release -> commission GS1 serials -> SSCC aggregate -> dispatch released warehouse lot.
11. **Java Service Injection**: Inject `PharmaManufacturingService`, `PharmaQmsService`, and `PharmaSerializationService`.
12. **Extension Points (SPIs)**: `PharmaBatchReleasePolicySpi`, `ElectronicSignatureSpi` (plug in HSM/PKI/OIDC step-up), and `PharmaSerializationProviderSpi` (plug in EU FMD / US DSCSA / GS1 EPCIS hubs).
13. **Audit Trail & ALCOA+ Controls**: `@ClinicalAudit` immutable audit chain + optimistic locking (`@Version`) + SHA-256 signature digests.
14. **ERP & MES Integration**: Combine with `healthcore-starter-integration-ops` to exchange SAP/Odoo/Oracle ERP material movements via transactional outbox + DLQ replay.
15. **Standards Alignment**: WHO GMP, ICH Q7–Q10, EU GMP Annex 11, FDA 21 CFR Part 11 technical controls, GS1 AI `(01)/(17)/(10)/(21)` & 18-digit SSCC.
16. **Minimal Working Example**: `examples/pharma-manufacturing` (port `8084`, zero clinical modules).
17. **Smoke Verification Commands**: `cd examples/pharma-manufacturing && docker compose up -d && mvn spring-boot:run`.
18. **Testing Strategy**: `PharmaManufacturingServiceTest`, `PharmaQmsServiceTest`, `PharmaSerializationServiceTest`, `CapabilityPackIsolationTest`.
19. **Common Pitfalls & Troubleshooting**: Self-approval triggers `SecurityException`; dispensing before line clearance or shipping a quarantined/excursion/recalled lot triggers `IllegalStateException`; closing a `CRITICAL` CAPA without `effectivenessVerified=true` triggers `IllegalStateException`.
20. **Upgrade & Compatibility Notes**: Each pharma starter can also be deployed as an independent microservice if plants separate MES, QMS, and EPCIS serialization workloads.
