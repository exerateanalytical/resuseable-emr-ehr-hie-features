# Standalone Pharmacy Commerce Starter — 20-Section Integration Guide

**Artifact**: `com.opeshealth.healthcore:healthcore-starter-pharmacy-commerce:1.0.0` | **Parent Pack**: Pack 01 (`healthcore-pack-pharmacy`)

## 1. Purpose & Business Scope
Provides standalone, hospital-free Spring Boot auto-configuration, domain entities, transactional services, Liquibase migrations, and REST APIs for **Standalone Pharmacy Commerce Starter**.

## 2. Target Audience & Non-Goals
Intended for backend Java 25 / Spring Boot 4.0 engineering teams building vertical applications. Does **not** require installing EMR, MPI, or Hospital HIS modules.

## 3. Maven & Gradle Installation
```xml
<dependency>
    <groupId>com.opeshealth.healthcore</groupId>
    <artifactId>healthcore-starter-pharmacy-commerce</artifactId>
</dependency>
```
*(Import `com.opeshealth.healthcore:healthcore-bom:1.0.0` in `<dependencyManagement>`.)*

## 4. Transitive Dependencies & Isolation Guarantee
Depends only on `healthcore-persistence-core` and `healthcore-security-audit` (plus `healthcore-starter-terminology-cdss` for pharmacy-commerce). Verified by `CapabilityPackIsolationTest` to pull zero unrelated HIS/EMR/MPI starters.

## 5. Java Package & Auto-Configuration
- Root package: `com.opeshealth.healthcore.pharmacy.commerce`
- Registered via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

## 6. Database Schema & Liquibase Changelogs
- Changelogs: `classpath:db/changelog/pharmacy_commerce/001-pharmacy-commerce.xml + 002-pharmacy-commerce-workflows.xml`
- Tables: `hc_pharm_products`, `hc_pharm_batches`, `hc_pharm_prescriptions`, `hc_pharm_pos_sales`, `hc_pharm_suppliers`, `hc_pharm_purchase_orders`, `hc_pharm_stock_transfers`, `hc_pharm_stock_adjustments`
- Portable across PostgreSQL 16+, Microsoft SQL Server 2022+, MySQL 8.4+, and H2.

## 7. Required Configuration Properties & Environment Variables
Set `DB_URL`, `DB_USER`, `DB_PASSWORD`, `spring.jpa.hibernate.ddl-auto=validate`, and `spring.liquibase.change-log` (either the parent pack master changelog or a Mode A master changelog defining `uuid_type`, `ts_type`, `timestamp_type`, `clob_type`, `bool_type`).

## 8. Multi-Tenant & Facility Isolation
Every JPA entity declares `@Filter(name = "facilityFilter", condition = "facility_id = :facilityId")`. Clients must supply `X-Facility-Id: <UUIDv7>` on every request.

## 9. Authentication, RBAC Roles & Permissions
Endpoints enforce Spring Security `@PreAuthorize` with permissions: `PERM_pharmacy:dispense`, `PERM_inventory:manage`, `PERM_billing:collect`, `PERM_facility:manage`.

## 10. Core Workflows & Business Rules
Product catalog & low-stock alerts, Supplier & PO goods receipt, FEFO lot allocation, Inter-branch transfers, Stock adjustments (expiry/damage/return), Rx partial/refill dispensing with counseling notes, Controlled-substance maker-checker verification, POS checkout. See `docs/workflows/WORKFLOW_STATE_MACHINES.md` for full state transition diagrams and guard rules.

## 11. REST API Reference
Base path: `/api/v1/pharmacy/commerce`. Import `examples/standalone-pharmacy/api/postman-collection.json` for ready-to-run HTTP requests.

## 12. Domain Events & Audit Trail
Every state-changing operation is annotated with `@ClinicalAudit` and persists tamper-evident audit metadata via `healthcore-security-audit`.

## 13. Service Provider Interfaces (SPIs) & Extension Points
Customizable Spring beans: `PharmacyInventoryValuationSpi`, `ElectronicSignatureSpi`. Register your own `@Bean` implementing the SPI to override default behavior without forking HealthCore.

## 14. External Application Integration Patterns
Supports in-process Spring Boot embedding, REST microservice gateway integration, and B2B webhook/API-key consumption (see `docs/integrations/EXTERNAL_APPLICATIONS_INTEGRATION_GUIDE.md`).

## 15. Security, Privacy & Data Integrity Controls
Enforces maker-checker dual verification via `ElectronicSignatureSpi` (SHA-256 tamper digests), optimistic locking (`@Version`), and strict tenant scoping.

## 16. Standards Applicability & Honest Boundaries
WHO GPP, GS1 GTIN/Lot/Expiry, WHO ATC/INN (technical controls; organizational pharmacy licensing required). Technical controls in software do not by themselves confer regulatory accreditation or certification.

## 17. Synthetic Seed Data & Local Verification
Use `examples/standalone-pharmacy/seed/synthetic-dataset.json` (100% synthetic, zero PII), `examples/standalone-pharmacy/docker-compose.yml`, and `examples/standalone-pharmacy/scripts/smoke-test.sh`.

## 18. Observability, Health Checks & Metrics
Exposes `/actuator/health`, `/actuator/info`, and `/actuator/prometheus` via Spring Boot Actuator.

## 19. Troubleshooting & Common Pitfalls
See `docs/operations/VERSIONING_UPGRADES_AND_CONTRIBUTION.md` for Liquibase property resolution, `FacilityContext` binding, and maker-checker diagnostics.

## 20. Versioning & Upgrade Path
Follows `healthcore-bom` semantic versioning (`1.0.0`). Future schema changes ship exclusively as additive `00N-*.xml` Liquibase files.
