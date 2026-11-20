# Pack 01 Integration Guide — Standalone Pharmacy & Pharmacy Chains (`healthcore-pack-pharmacy`)

1. **Purpose & Scope**: Independent retail/community pharmacies, multi-branch pharmacy chains, and e-pharmacy backend APIs.
2. **Target Use Cases**: Product catalog management, supplier procurement & goods receipt, FEFO lot/expiry inventory, prescription verification/dispensing/refills, controlled-drug dual witness, and recall quarantine.
3. **Included vs Excluded Modules**: Includes `healthcore-persistence-core`, `healthcore-security-audit`, `healthcore-starter-terminology-cdss`, `healthcore-starter-pharmacy-commerce`. Strictly excludes EMR, LIS, inpatient ADT, surgery/OT, mortuary, and pharma manufacturing.
4. **Prerequisites**: Java 25, Spring Boot 4.0, Maven 3.9+, PostgreSQL 16+ / SQL Server 2022 / MySQL 8.4+.
5. **Maven BOM & Dependency Setup**:
```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.opeshealth.healthcore</groupId>
            <artifactId>healthcore-bom</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
<dependencies>
    <dependency>
        <groupId>com.opeshealth.healthcore</groupId>
        <artifactId>healthcore-pack-pharmacy</artifactId>
    </dependency>
</dependencies>
```
6. **Database & Liquibase Setup**: Set `spring.liquibase.change-log: classpath:db/changelog/packs/pack-pharmacy-master.xml` and `spring.jpa.hibernate.ddl-auto: validate`.
7. **Required Environment Variables**: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `HEALTHCORE_OIDC_ISSUER_URI` (see `examples/standalone-pharmacy/.env.example`).
8. **Security & Multi-Tenant Context**: Pass `Authorization: Bearer <OIDC-JWT>` and `X-Facility-Id: <branch-uuid>`. Required authorities: `PERM_pharmacy:dispense`, `PERM_inventory:adjust`, `PERM_facility:manage`.
9. **Core REST Endpoints**:
   - `POST /api/v1/pharmacy-commerce/products`
   - `POST /api/v1/pharmacy-commerce/goods-receipt`
   - `POST /api/v1/pharmacy-commerce/dispense`
   - `POST /api/v1/pharmacy-commerce/recall/{batchNumber}`
   - `GET /api/v1/pharmacy-commerce/alerts/near-expiry?days=90`
10. **End-to-End Sample Workflow**: Create SKU -> receive supplier PO batch (validated via `TraceableLotPrimitive`) -> dispense via FEFO with independent witness for controlled drugs -> quarantine recalled lot.
11. **Java Service & Repository Injection**: Inject `PharmacyCommerceService` directly inside Spring services or batch jobs.
12. **Extension Points (SPIs)**: Register a Spring bean implementing `PharmacyCommerceService.PharmacyDispensingPolicySpi` to add country-specific substitution, generic preference, or payer copay checks.
13. **Domain Events & Audit Trail**: Every product creation, goods receipt, FEFO dispense, and recall quarantine emits `@ClinicalAudit` tamper-evident records.
14. **Offline & Sync Readiness**: Optionally add `healthcore-starter-offline-sync` for branch pharmacies operating over intermittent links.
15. **Standards & Interoperability**: GS1 GTIN-14/lot/expiry validation (`TraceableLotPrimitive`), ATC/RxNorm product coding via `healthcore-starter-terminology-cdss`.
16. **Minimal Working Example**: `examples/standalone-pharmacy` (runs on port `8081` with zero EMR/LIS/HIS modules).
17. **Smoke Verification Commands**: See `examples/standalone-pharmacy/README.md` (`mvn spring-boot:run` + `curl` against `/api/v1/pharmacy-commerce/alerts/near-expiry`).
18. **Testing Strategy**: Unit test `PharmacyCommerceServiceTest` + `CapabilityPackIsolationTest`.
19. **Common Pitfalls & Troubleshooting**: Missing `X-Facility-Id` returns `403 AccessDeniedException`; dispensing a controlled drug where `witnessId == pharmacistId` throws `SecurityException`; receiving expired stock throws `IllegalStateException`.
20. **Upgrade & Compatibility Notes**: Semantic versioning via `healthcore-bom`; Liquibase migrations are append-only and idempotent across PostgreSQL, MSSQL, and MySQL.
