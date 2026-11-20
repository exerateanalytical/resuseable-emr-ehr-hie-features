# Pack 05 Integration Guide — Outpatient & Specialty EMR (`healthcore-pack-emr`)

1. **Purpose & Scope**: Outpatient clinics, physician practices, specialty clinics, and ambulatory telemedicine centers.
2. **Target Use Cases**: Patient registration (MPI), appointments/scheduling, clinical encounters (SOAP notes, vitals, allergies, diagnoses), CPOE orders & results inbox, CDSS checks, and workflow tasks.
3. **Included vs Excluded Modules**: Includes `mpi`, `terminology-cdss`, `scheduling`, `emr-clinical`, `orders-results`, `workflow-engine`. Strictly excludes inpatient ADT/beds (`hospital-ops`), surgery/OT, mortuary, workforce payroll, pharma manufacturing, and biobanking.
4. **Prerequisites**: Java 25, Spring Boot 4.0, Maven 3.9+, PostgreSQL 16+ / SQL Server 2022 / MySQL 8.4+.
5. **Maven Setup**: Import `healthcore-bom:1.0.0` and depend on `com.opeshealth.healthcore:healthcore-pack-emr`.
6. **Database & Liquibase Setup**: `spring.liquibase.change-log: classpath:db/changelog/packs/pack-emr-master.xml`.
7. **Required Environment Variables**: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `HEALTHCORE_OIDC_ISSUER_URI`.
8. **Security & Multi-Tenant Context**: OIDC JWT + `X-Facility-Id`, RBAC permissions (`PERM_patient:write`, `PERM_encounter:sign`, `PERM_order:create`), and break-glass audit support.
9. **Core REST Endpoints**: `/api/v1/patients`, `/api/v1/scheduling/**`, `/api/v1/encounters/**`, `/api/v1/orders-results/**`, `/api/v1/workflows/**`.
10. **End-to-End Sample Workflow**: Register patient in MPI -> schedule appointment -> check-in -> record vitals & SOAP encounter -> place outpatient order -> sign note & generate discharge/after-visit summary.
11. **Java Service Injection**: Inject `PatientMpiService`, `EmrClinicalWorkflowService`, `SchedulingService`, `OrdersResultsService`, and `WorkflowEngineService`.
12. **Extension Points (SPIs)**: `ClinicalDecisionSupportSpi`, `TerminologyLookupSpi`, `WorkflowActionHandlerSpi`.
13. **Domain Events & Audit Trail**: `@ClinicalAudit` logs patient access, note signing, addenda, and order verification.
14. **Optional Add-Ons**: Add `healthcore-starter-telemedicine`, `healthcore-starter-uhc-billing`, `healthcore-starter-patient-portal`, or `healthcore-starter-offline-sync` without installing HIS.
15. **Standards Alignment**: ICD-10/ICD-11, SNOMED CT, LOINC, RxNorm terminology tables via `terminology-cdss`.
16. **Minimal Working Example**: `examples/outpatient-emr` (port `8088`).
17. **Smoke Verification Commands**: `cd examples/outpatient-emr && docker compose up -d && mvn spring-boot:run`.
18. **Testing Strategy**: `EmrClinicalWorkflowServiceTest`, `SchedulingServiceTest`, `OrdersResultsServiceTest`, `CapabilityPackIsolationTest`.
19. **Common Pitfalls & Troubleshooting**: Signed clinical notes are immutable; corrections must be recorded as linked addenda.
20. **Upgrade & Compatibility Notes**: Seamless zero-migration upgrade path to `healthcore-pack-ehr` or `healthcore-pack-his` simply by swapping the pack dependency and Liquibase master include.
