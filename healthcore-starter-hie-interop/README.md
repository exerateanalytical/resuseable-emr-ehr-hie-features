# HIE Interoperability (FHIR R4, HL7 v2.5.1, IHE, DICOMweb, CDA R2) Starter (`healthcore-starter-hie-interop`)

## 1. Purpose
Provides tenant-scoped FHIR R4 Transaction/Batch bundles (Patient & DocumentReference), honest CapabilityStatement, partial Patient Summary ($summary), Bulk NDJSON export, HMAC-signed Subscriptions, full HL7 v2.5.1 ADT/ORM/ORU/SIU/DFT persistence with MSH-10 idempotency & DLQ, IHE PIXm/PDQm/MHD/mCSD, ASTM E1394 parsing, CDA R2 XML, and hardened DICOMweb PACS proxy.

## 2. Implemented Capabilities
- Spring Boot 4.0 auto-configured starter (`AutoConfiguration.imports` + `@EnableJpaRepositories(considerNestedRepositories = true)`).
- Tenant-scoped domain services, JPA entities, and secured REST/standards endpoints.
- Cross-database Liquibase schema support (PostgreSQL, Microsoft SQL Server, MySQL).

## 3. Explicitly Unsupported Capabilities
Does not claim full HL7 UV IPS certification, full FHIR R4 resource coverage beyond CapabilityStatement, or local DICOM binary object storage without external PACS.

## 4. Domain Model
- `DicomStudyEntity` (`hc_dicom_studies`)
- `FhirSubscriptionEntity` (`hc_fhir_subscriptions`)
- `HieDlqMessageEntity` (`hc_hie_dlq_messages`)
- `Hl7FinancialTransactionEntity` (`hc_hl7_financial_txns`)
- `Hl7ObservationResultEntity` (`hc_hl7_results`)
- `Hl7OrderRecordEntity` (`hc_hl7_orders`)
- `Hl7ScheduleEventEntity` (`hc_hl7_appointments`)
- `IheDocumentEntity` (`hc_ihe_documents`)

## 5. Database Migrations
- `db/changelog/hie/001-hie-interop.xml`
- `db/changelog/hie/002-hie-ihe-docs.xml`
- `db/changelog/hie/003-hie-hl7-clinical-tables.xml`

## 6. REST APIs
- `POST /api/v1/hie/dicom/pacs/studies`
- `GET /api/v1/hie/dicom/pacs/studies/{studyUid}`
- `GET /api/v1/hie/dicom/pacs/studies/{studyUid}/series/{seriesUid}/instances/{sopUid}`
- `GET /api/v1/hie/dicom/pacs/qido`
- `GET /fhir/r4/metadata`
- `GET /fhir/r4/Patient/{id}/$summary`
- `GET /fhir/r4/Patient/$export`
- `POST /fhir/r4`
- `POST /fhir/r4/Subscription`
- `POST /fhir/r4/Subscription/{id}/$notify`
- `GET /fhir/r4/Patient/$ihe-pix`
- `GET /fhir/r4/Patient`
- `POST /fhir/r4/DocumentReference`
- `GET /fhir/r4/DocumentReference`
- `GET /fhir/r4/Organization`
- `GET /fhir/r4/Location`
- `POST /api/v1/hie/hl7v2/ingest`
- `POST /api/v1/hie/astm/parse`
- `POST /api/v1/hie/dicom/studies`
- `POST /api/v1/hie/dicom/stow-rs`
- `GET /api/v1/hie/dicom/studies`
- `GET /api/v1/hie/cda/{patientId}`

## 7. FHIR / HL7 / Standards Mappings
- Maps internal domain identifiers using UUIDv7 and standard clinical terminologies (LOINC, SNOMED CT, ICD-10/11, RxNorm, UCUM) where applicable.
- See `docs/API_CONFORMANCE_REPORT.md` for verified FHIR R4, HL7 v2.5.1, IHE, and DICOMweb conformance boundaries.

## 8. Authorization Model
- Zero `or isAuthenticated()` fallbacks on clinical or patient-data endpoints.
- Method-level `@PreAuthorize` guards detected in this module:
- `hasAuthority('PERM_dicom:index') or hasAuthority('PERM_imaging:report:sign') or hasAuthority('PERM_lab:verify')`
- `hasAuthority('PERM_dicom:index') or hasAuthority('PERM_imaging:report:sign')`
- `hasAuthority('PERM_lab:result:enter') or hasAuthority('PERM_lab:verify')`
- `hasAuthority('PERM_patient:read') and (hasAuthority('PERM_audit:export') or hasAuthority('PERM_system:admin'))`
- `hasAuthority('PERM_patient:read') and (hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:sign'))`
- `hasAuthority('PERM_patient:read')`
- `hasAuthority('PERM_patient:write') or hasAuthority('PERM_lab:result:enter')`
- `hasAuthority('PERM_system:admin') or hasAuthority('PERM_facility:manage')`

## 9. Tenant Scope
- All clinical and operational records are scoped by `facility_id` via `FacilityContext`, JWT `facility_id` validation, Hibernate `facilityFilter`, and PostgreSQL Row-Level Security (`PostgresRlsSessionAspect`).

## 10. Audit Events
`DICOM_STOW_RS_PROXY`

## 11. Configuration
- Configured via standard Spring Boot properties and environment variables documented in `.env.example` and `docs/PRODUCTION_RUNBOOK.md`.
- No hardcoded production secrets; `ProductionSecretsStartupValidator` fails closed in production profiles.

## 12. Deployment Requirements
- Java 25 (JDK 25), Spring Boot 4.0, PostgreSQL 16+ (primary) or MSSQL / MySQL, and OIDC/Keycloak issuer.
- Include `com.opeshealth.healthcore:healthcore-starter-hie-interop:1.0.0` with `healthcore-bom`.

## 13. Failure Behavior
- Fails closed (`AccessDeniedException` / `SecurityException` / `IllegalStateException`) on missing tenant context, unauthorized access, invalid clinical state transitions, or missing mandatory safety context.
- Never returns fabricated `200 OK` responses for unsupported operations.

## 14. Clinical Validation Status
- **Status:** PRE-PRODUCTION / ENGINEERING BUILD.
- Standards profiles require independent HL7/IHE/DICOM conformance testing with target exchange partners.

## 15. Test Evidence
- Module test classes:
- `HieInteropSecurityConfigurationTest.java`
- `DicomPacsProxyServiceTest.java`
- `HieFhirAndHl7ConformanceTest.java`
- Verification command (JDK 25 CI): `mvn -B -ntp test -pl healthcore-starter-hie-interop -am`

## 16. Known Limitations
- Live Maven/JUnit/Testcontainers execution must be verified in JDK 25 + Docker CI.
- Refer to `docs/KNOWN_LIMITATIONS_REGISTER.md` for full platform-level caveats and go-live gates.
