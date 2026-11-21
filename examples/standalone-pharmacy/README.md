# Standalone Pharmacy — HealthCore SDK reference consumer

**Status: pre-production / independent PostgreSQL verification in progress.** This is a Java 25/Spring Boot 4 reference implementation of a community/chain pharmacy, not a licensed dispensary application or a ready-for-patient-care product.

## Problem it addresses

Pharmacy teams need a traceable link between approved suppliers, purchasing, lots, expiry, product inventory, prescription dispensing and recalls. The `healthcore-pack-pharmacy` composes `healthcore-starter-pharmacy-commerce`, terminology, persistence and security independently of EMR, LIS, inpatient, surgery or hospital systems.

### Available domain functions

- Product catalog with SKU, medicine code, unit price and controlled-substance flag
- Supplier qualification and purchase order creation
- Goods receipt with GTIN, lot/batch, expiry and stock balance
- First-expiring-first-out (FEFO) lot selection for dispensing
- Recalled/quarantined and expired stock exclusion from dispensing
- Partial fills/refills, controlled-substance second-witness requirement and counseling notes
- Transfers, adjustments, near-expiry and low-stock reports

**Limitations are material:** The reference service does not prove an externally authenticated e-prescription, actual pharmacist licensure and authorization, insurer copay settlement, bank/payment integration, controlled-substance reporting, stock reconciliation across multiple branches or offline recovery. It also needs an auditable procurement rule that rejects receipts without a verified matching purchase order, independent maker-checker signatures, return/refund workflows and verified point-of-sale settlement. Do not interpret the test's human-readable pharmacist ID as actual license validation.

## Independent workflow test

The new `IndependentWorkflowIT` is an actual Spring Boot consumer plus PostgreSQL 16 Testcontainers scenario, not merely a classpath check. Synthetic flows include:

1. Qualified supplier → product/PO → receipt of an early-expiring and later-expiring lot.
2. Dispense medicine from the earliest eligible lot, persist stock decrement and verify quantity through a fresh SQL connection.
3. Quarantine a recalled early lot and dispense the next eligible later lot without touching quarantined stock.
4. Verify near-expiry alerts and persisted purchase-order quantities.
5. Reject an unqualified supplier, expired inbound goods, same-person controlled-substance witness and wrong-tenant dispensing.

The `WorkflowSmokeTest` only checks package presence and **does not** count as E2E evidence.

### Execute on JDK 25

Requires JDK 25, Maven 3.9+, Docker/Testcontainers and PostgreSQL 16 image access. Use only synthetic test records.

```bash
# From repository root, install dependencies and the selected capability pack
mvn -B -ntp -pl healthcore-bom,packs/healthcore-pack-pharmacy -am clean install -DskipITs=true

# From repository root, run the actual independently packaged pharmacy
mvn -B -ntp -f examples/standalone-pharmacy/pom.xml clean verify -DskipITs=false
```

The branch-specific workflow `.github/workflows/standalone-pharmacy-branch-assurance.yml` runs the same tests under Temurin JDK25 and fails without non-skipped `IndependentWorkflowIT` XML evidence. **Passing the synthetic IT is necessary but not sufficient for production deployment.**

## Authentication and compliance

Healthcare facility identity must be obtained from a verified JWT/approved tenant-membership context; `X-Facility-Id` alone is not authorization. All real pharmacy dispensing requires institution-specific licensing, clinical prescription verification, segregation of duties, pharmacovigilance/recall procedures, financial and data-protection checks and independent acceptance testing. Configure and test an actual OIDC provider before exposing clinical REST endpoints.

For completeness requirements see the repository's capability-pack matrix, security threat model, clinical safety case and release-readiness register.
