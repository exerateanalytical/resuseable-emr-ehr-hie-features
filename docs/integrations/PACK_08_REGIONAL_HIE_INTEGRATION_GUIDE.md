# Pack 08 Integration Guide — Regional & National HIE (`healthcore-pack-hie`)

1. **Purpose & Scope**: Regional, municipal, and national Health Information Exchanges (HIEs), Ministries of Health interoperability backbones, and multi-vendor health data hubs.
2. **Target Use Cases**: Cross-enterprise Master Patient Index (EMPI), terminology normalization, FHIR R4 / HL7 v2 / IHE XDS.b & PIX/PDQ brokering, Dead-Letter Queue (DLQ) replay & connector health monitoring, and developer API product governance.
3. **Included vs Excluded Modules**: Includes `mpi`, `terminology-cdss`, `hie-interop`, `fhir-hie`, `interoperability-ihe`, `integration-ops`, `api-product`. Strictly excludes inpatient beds, surgery/OT, billing, pharmacy, and pharmaceutical manufacturing.
4. **Prerequisites**: Java 25, Spring Boot 4.0, Maven 3.9+, PostgreSQL 16+ / SQL Server 2022 / MySQL 8.4+.
5. **Maven Setup**: Import `healthcore-bom:1.0.0` and depend on `com.opeshealth.healthcore:healthcore-pack-hie`.
6. **Database & Liquibase Setup**: `spring.liquibase.change-log: classpath:db/changelog/packs/pack-hie-master.xml`.
7. **Required Environment Variables**: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `HEALTHCORE_OIDC_ISSUER_URI`, `HEALTHCORE_FHIR_BASE_URL`.
8. **Security & Trust Fabric**: Mutual TLS / OIDC JWT, tenant/facility organization scoping, API key & Bucket4j rate limiting (`api-product`), and ATNA-style `@ClinicalAudit` immutable logs.
9. **Core REST & Standards Endpoints**:
   - FHIR R4: `/fhir/r4/{Patient,Encounter,Observation,Bundle}`
   - IHE: `/api/v1/ihe/{pix,pdq,xds}`
   - Integration Ops: `/api/v1/integration-ops/{routes,dlq,health}`
   - API Product: `/api/v1/developer-portal/{keys,webhooks}`
10. **End-to-End Sample Workflow**: Link multi-facility patient identities in EMPI -> route inbound HL7 v2 / FHIR R4 clinical documents -> capture failed partner deliveries in DLQ -> replay with idempotency key -> expose governed OpenAPI developer portal.
11. **Java Service Injection**: Inject `PatientMpiService`, `HieInteropService`, `IheInteroperabilityService`, `IntegrationOpsService`, and `ApiProductService`.
12. **Extension Points (SPIs)**: `ExternalHieConnectorSpi`, `WebhookDeliverySpi`, `MpiMatchingAlgorithmSpi`.
13. **Domain Events & Audit Trail**: Outbox + DLQ replay logs + `@ClinicalAudit` cross-organization access audit.
14. **Optional Public Health Add-Ons**: Add `healthcore-starter-disease-surveillance`, `healthcore-starter-deidentification-research`, or `healthcore-starter-analytics` for national IDSR/DHIS2 reporting.
15. **Standards Alignment**: HL7 FHIR R4, HL7 v2.5.1, IHE PIX/PDQ/XDS.b/ATNA patterns, OpenAPI 3.1.
16. **Minimal Working Example**: `examples/regional-hie` (port `8091`).
17. **Smoke Verification Commands**: `cd examples/regional-hie && docker compose up -d && mvn spring-boot:run`.
18. **Testing Strategy**: `IntegrationOpsServiceTest`, `HieInteropServiceTest`, `CapabilityPackIsolationTest`.
19. **Common Pitfalls & Troubleshooting**: Always supply `Idempotency-Key` headers on inbound partner webhooks and HIE feeds to guarantee exactly-once document ingestion.
20. **Upgrade & Compatibility Notes**: Horizontally stateless behind any L7 load balancer; Redis recommended for distributed Bucket4j rate-limit counters.
