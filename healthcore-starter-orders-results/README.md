# Unified Orders & Results Starter (`healthcore-starter-orders-results`)

## 1. Purpose
Provides cross-domain clinical order sets, unified order lifecycle (lab, imaging, medication, procedure, referral), specimen collection, result entry/verification/amendment, reflex order rules, closed-loop critical result acknowledgment, and FHIR ServiceRequest/DiagnosticReport projections.

## 2. Implemented Capabilities
- Spring Boot 4.0 auto-configured starter (`AutoConfiguration.imports` + `@EnableJpaRepositories(considerNestedRepositories = true)`).
- Tenant-scoped domain services, JPA entities, and secured REST/standards endpoints.
- Cross-database Liquibase schema support (PostgreSQL, Microsoft SQL Server, MySQL).

## 3. Explicitly Unsupported Capabilities
Does not embed proprietary laboratory analyzer serial/TCP device drivers beyond ASTM/HL7 gateways.

## 4. Domain Model
- `ClinicalOrderSetEntity` (`hc_order_sets`)
- `ClinicalResultEntity` (`hc_clinical_results`)
- `OrderSpecimenEntity` (`hc_order_specimens`)
- `ReflexOrderRuleEntity` (`hc_reflex_order_rules`)
- `UnifiedClinicalOrderEntity` (`hc_unified_orders`)

## 5. Database Migrations
- `db/changelog/orders_results/001-orders-results.xml`

## 6. REST APIs
- `POST /api/v1/orders-results/order-sets`
- `POST /api/v1/orders-results/orders`
- `POST /api/v1/orders-results/orders/from-set/{setCode}`
- `GET /api/v1/orders-results/orders`
- `POST /api/v1/orders-results/orders/{orderId}/acknowledge`
- `POST /api/v1/orders-results/orders/{orderId}/specimen`
- `POST /api/v1/orders-results/orders/{orderId}/cancel`
- `POST /api/v1/orders-results/results`
- `POST /api/v1/orders-results/results/{resultId}/verify`
- `POST /api/v1/orders-results/results/{resultId}/amend`
- `POST /api/v1/orders-results/results/{resultId}/acknowledge-critical`
- `GET /api/v1/orders-results/results/critical-unacknowledged`
- `POST /api/v1/orders-results/reflex-rules`
- `GET /api/v1/orders-results/fhir/ServiceRequest`
- `GET /api/v1/orders-results/fhir/DiagnosticReport`

## 7. FHIR / HL7 / Standards Mappings
- Maps internal domain identifiers using UUIDv7 and standard clinical terminologies (LOINC, SNOMED CT, ICD-10/11, RxNorm, UCUM) where applicable.
- See `docs/API_CONFORMANCE_REPORT.md` for verified FHIR R4, HL7 v2.5.1, IHE, and DICOMweb conformance boundaries.

## 8. Authorization Model
- Zero `or isAuthenticated()` fallbacks on clinical or patient-data endpoints.
- Method-level `@PreAuthorize` guards detected in this module:
- `hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:verify')`
- `hasAuthority('PERM_facility:manage') or hasAuthority('PERM_order:sign')`
- `hasAuthority('PERM_lab:accession') or hasAuthority('PERM_rx:dispense') or hasAuthority('PERM_order:sign')`
- `hasAuthority('PERM_lab:accession') or hasAuthority('PERM_vitals:write')`
- `hasAuthority('PERM_lab:result:enter') or hasAuthority('PERM_imaging:report:sign')`
- `hasAuthority('PERM_lab:verify') or hasAuthority('PERM_imaging:report:sign')`
- `hasAuthority('PERM_order:sign') or hasAuthority('PERM_encounter:sign')`
- `hasAuthority('PERM_order:sign') or hasAuthority('PERM_rx:prescribe')`
- `hasAuthority('PERM_order:sign')`
- `hasAuthority('PERM_patient:read')`

## 9. Tenant Scope
- All clinical and operational records are scoped by `facility_id` via `FacilityContext`, JWT `facility_id` validation, Hibernate `facilityFilter`, and PostgreSQL Row-Level Security (`PostgresRlsSessionAspect`).

## 10. Audit Events
`ORDERS_APPLY_ORDER_SET`, `ORDERS_CANCEL`, `ORDERS_PLACE_ORDER`, `RESULTS_AMEND`, `RESULTS_ENTER_PRELIMINARY`, `RESULTS_VERIFY_FINAL`

## 11. Configuration
- Configured via standard Spring Boot properties and environment variables documented in `.env.example` and `docs/PRODUCTION_RUNBOOK.md`.
- No hardcoded production secrets; `ProductionSecretsStartupValidator` fails closed in production profiles.

## 12. Deployment Requirements
- Java 25 (JDK 25), Spring Boot 4.0, PostgreSQL 16+ (primary) or MSSQL / MySQL, and OIDC/Keycloak issuer.
- Include `com.opeshealth.healthcore:healthcore-starter-orders-results:1.0.0` with `healthcore-bom`.

## 13. Failure Behavior
- Fails closed (`AccessDeniedException` / `SecurityException` / `IllegalStateException`) on missing tenant context, unauthorized access, invalid clinical state transitions, or missing mandatory safety context.
- Never returns fabricated `200 OK` responses for unsupported operations.

## 14. Clinical Validation Status
- **Status:** PRE-PRODUCTION / ENGINEERING BUILD.
- Critical-value thresholds and reflex rules require hospital pathology/medical-board sign-off.

## 15. Test Evidence
- Module test classes:
- Covered via `healthcore-quickstart-host` integration/safety suites.
- Verification command (JDK 25 CI): `mvn -B -ntp test -pl healthcore-starter-orders-results -am`

## 16. Known Limitations
- Live Maven/JUnit/Testcontainers execution must be verified in JDK 25 + Docker CI.
- Refer to `docs/KNOWN_LIMITATIONS_REGISTER.md` for full platform-level caveats and go-live gates.
