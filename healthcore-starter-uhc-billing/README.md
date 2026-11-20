# Revenue Cycle, Double-Entry Ledger & UHC/CBHI Billing Starter (`healthcore-starter-uhc-billing`)

## 1. Purpose
Provides idempotent double-entry debit/credit patient ledger postings, currency-safe minor-unit math, reversal/refund balancing, account reconciliation reports, CBHI/UHC eligibility/claims, and payment gateway SPI.

## 2. Implemented Capabilities
- Spring Boot 4.0 auto-configured starter (`AutoConfiguration.imports` + `@EnableJpaRepositories(considerNestedRepositories = true)`).
- Tenant-scoped domain services, JPA entities, and secured REST/standards endpoints.
- Cross-database Liquibase schema support (PostgreSQL, Microsoft SQL Server, MySQL).

## 3. Explicitly Unsupported Capabilities
Does not include live production payer clearinghouse credentials or X12 837/835 EDI partner certifications out of the box.

## 4. Domain Model
- `CbhiPolicyEntity` (`hc_cbhi_policies`)
- `InsuranceClaimEntity` (`hc_insurance_claims`)
- `LedgerEntryEntity` (`hc_ledger_entries`)
- `PatientAccountEntity` (`hc_patient_accounts`)
- `PaymentTransactionEntity` (`hc_payment_transactions`)
- `TariffScheduleEntity` (`hc_tariff_schedules`)
- `UhcVoucherEntity` (`hc_uhc_vouchers`)

## 5. Database Migrations
- `db/changelog/uhc_billing/001-uhc-billing.xml`
- `db/changelog/uhc_billing/002-uhc-vouchers.xml`
- `db/changelog/uhc_billing/003-uhc-double-entry.xml`

## 6. REST APIs
- `POST /api/v1/uhc/policies`
- `GET /api/v1/uhc/policies/{policyNumber}/offline-qr`
- `POST /api/v1/uhc/policies/verify-offline-qr`
- `POST /api/v1/uhc/vouchers`
- `POST /api/v1/uhc/vouchers/{voucherCode}/redeem`
- `POST /api/v1/uhc/charges`
- `POST /api/v1/uhc/journals/{journalBatchId}/reverse`
- `GET /api/v1/uhc/accounts/{patientId}/reconciliation`
- `POST /api/v1/uhc/claims/adjudicate`
- `GET /fhir/r4/Coverage`
- `POST /fhir/r4/Claim`
- `POST /api/v1/uhc/webhooks/{providerCode}`

## 7. FHIR / HL7 / Standards Mappings
- Maps internal domain identifiers using UUIDv7 and standard clinical terminologies (LOINC, SNOMED CT, ICD-10/11, RxNorm, UCUM) where applicable.
- See `docs/API_CONFORMANCE_REPORT.md` for verified FHIR R4, HL7 v2.5.1, IHE, and DICOMweb conformance boundaries.

## 8. Authorization Model
- Zero `or isAuthenticated()` fallbacks on clinical or patient-data endpoints.
- Method-level `@PreAuthorize` guards detected in this module:
- `hasAuthority('PERM_billing:invoice') or hasAuthority('PERM_audit:read')`
- `hasAuthority('PERM_billing:invoice') or hasAuthority('PERM_system:admin')`
- `hasAuthority('PERM_billing:invoice')`
- `hasAuthority('PERM_claim:submit') or hasAuthority('PERM_uhc:verify')`
- `hasAuthority('PERM_claim:submit')`
- `hasAuthority('PERM_uhc:verify') or hasAuthority('PERM_billing:collect')`
- `hasAuthority('PERM_uhc:verify') or hasAuthority('PERM_billing:invoice')`
- `hasAuthority('PERM_uhc:verify') or hasAuthority('PERM_patient:read')`

## 9. Tenant Scope
- All clinical and operational records are scoped by `facility_id` via `FacilityContext`, JWT `facility_id` validation, Hibernate `facilityFilter`, and PostgreSQL Row-Level Security (`PostgresRlsSessionAspect`).

## 10. Audit Events
`BILLING_POST_CHARGE`, `BILLING_REVERSE_JOURNAL`, `UHC_ADJUDICATE_CLAIM`, `UHC_REDEEM_VOUCHER`

## 11. Configuration
- Configured via standard Spring Boot properties and environment variables documented in `.env.example` and `docs/PRODUCTION_RUNBOOK.md`.
- No hardcoded production secrets; `ProductionSecretsStartupValidator` fails closed in production profiles.

## 12. Deployment Requirements
- Java 25 (JDK 25), Spring Boot 4.0, PostgreSQL 16+ (primary) or MSSQL / MySQL, and OIDC/Keycloak issuer.
- Include `com.opeshealth.healthcore:healthcore-starter-uhc-billing:1.0.0` with `healthcore-bom`.

## 13. Failure Behavior
- Fails closed (`AccessDeniedException` / `SecurityException` / `IllegalStateException`) on missing tenant context, unauthorized access, invalid clinical state transitions, or missing mandatory safety context.
- Never returns fabricated `200 OK` responses for unsupported operations.

## 14. Clinical Validation Status
- **Status:** PRE-PRODUCTION / ENGINEERING BUILD.
- Tariff schedules and clinical coding mappings require hospital finance & coding governance validation.

## 15. Test Evidence
- Module test classes:
- `UhcBillingReconciliationTest.java`
- Verification command (JDK 25 CI): `mvn -B -ntp test -pl healthcore-starter-uhc-billing -am`

## 16. Known Limitations
- Live Maven/JUnit/Testcontainers execution must be verified in JDK 25 + Docker CI.
- Refer to `docs/KNOWN_LIMITATIONS_REGISTER.md` for full platform-level caveats and go-live gates.
