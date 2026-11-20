# Pack 06 Integration Guide — Longitudinal EHR (`healthcore-pack-ehr`)

1. **Purpose & Scope**: Multi-clinic networks, integrated delivery systems, and longitudinal health record custodians needing cross-facility provenance, FHIR R4 / IPS patient summaries, and patient portal access.
2. **Target Use Cases**: Longitudinal problem/medication/allergy timelines, cross-facility provenance & reconciliation, International Patient Summary (IPS) FHIR R4 bundles, patient portal self-service, and structured clinical templates.
3. **Included vs Excluded Modules**: Extends `healthcore-pack-emr` with `hie-interop`, `fhir-hie`, `patient-portal`, `templates-dashboards`. Excludes inpatient bed management, surgery/OT, mortuary, pharma manufacturing, and biobanking by default.
4. **Prerequisites**: Java 25, Spring Boot 4.0, Maven 3.9+, PostgreSQL 16+ / SQL Server 2022 / MySQL 8.4+.
5. **Maven Setup**: Import `healthcore-bom:1.0.0` and depend on `com.opeshealth.healthcore:healthcore-pack-ehr`.
6. **Database & Liquibase Setup**: `spring.liquibase.change-log: classpath:db/changelog/packs/pack-ehr-master.xml`.
7. **Required Environment Variables**: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `HEALTHCORE_OIDC_ISSUER_URI`.
8. **Security & Consent**: Patient-scoped portal scopes (`PortalScope`), cross-facility consent checks, break-glass auditing, and FHIR SMART scopes.
9. **Core REST & FHIR Endpoints**: `/fhir/r4/Patient/{id}/$summary`, `/api/v1/portal/**`, `/api/v1/templates/**`, plus all Pack 05 EMR endpoints.
10. **End-to-End Sample Workflow**: Record multi-site encounters -> reconcile longitudinal medications/problems -> generate FHIR R4 IPS Bundle -> allow patient portal view & download.
11. **Java Service Injection**: Inject `HieInteropService`, `PatientPortalService`, `ClinicalTemplateService`, and Pack 05 EMR services.
12. **Extension Points (SPIs)**: `FhirResourceTransformerSpi`, `PatientPortalNotificationSpi`, `ConsentEnforcementSpi`.
13. **Domain Events & Audit Trail**: Full `@ClinicalAudit` tracking of IPS exports, portal reads, and cross-facility chart queries.
14. **Offline & Sync Readiness**: Optionally add `healthcore-starter-offline-sync` for distributed clinics syncing into a longitudinal EHR.
15. **Standards Alignment**: HL7 FHIR R4, International Patient Summary (IPS), HL7 v2, IHE XDS.b/PIX/PDQ (optional `interoperability-ihe`).
16. **Minimal Working Example**: `examples/longitudinal-ehr` (port `8089`).
17. **Smoke Verification Commands**: `cd examples/longitudinal-ehr && docker compose up -d && mvn spring-boot:run`.
18. **Testing Strategy**: `HieInteropServiceTest`, `PatientPortalServiceTest`, `CapabilityPackIsolationTest`.
19. **Common Pitfalls & Troubleshooting**: Ensure portal JWT claims bind explicitly to the authenticated patient UUID to prevent cross-patient data access.
20. **Upgrade & Compatibility Notes**: Direct additive upgrade to `healthcore-pack-his` when adding inpatient wards or emergency departments.
