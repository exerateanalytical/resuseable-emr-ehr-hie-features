# Offline Delta Sync & Device Revocation Governance Starter (`healthcore-starter-offline-sync`)

## 1. Purpose
Provides Hybrid Logical Clock (HLC) delta synchronization, conflict tracking, device enrollment registry, remote wipe/revocation enforcement blocking revoked devices from pushing mutations.

## 2. Implemented Capabilities
- Spring Boot 4.0 auto-configured starter (`AutoConfiguration.imports` + `@EnableJpaRepositories(considerNestedRepositories = true)`).
- Tenant-scoped domain services, JPA entities, and secured REST/standards endpoints.
- Cross-database Liquibase schema support (PostgreSQL, Microsoft SQL Server, MySQL).

## 3. Explicitly Unsupported Capabilities
Does not manage OS-level MDM (Android Enterprise / Apple MDM) hardware enrollment profiles.

## 4. Domain Model
- `SyncConflictEntity` (`hc_sync_conflicts`)
- `SyncDeltaRecordEntity` (`hc_sync_deltas`)
- `SyncDeviceEntity` (`hc_sync_devices`)
- `SyncTombstoneEntity` (`hc_sync_tombstones`)

## 5. Database Migrations
- `db/changelog/offline_sync/001-offline-sync.xml`
- `db/changelog/offline_sync/002-device-revocation.xml`

## 6. REST APIs
- `POST /api/v1/sync/devices`
- `POST /api/v1/sync/devices/{deviceNodeId}/revoke`
- `POST /api/v1/sync/devices/{deviceNodeId}/wipe-confirmed`
- `GET /api/v1/sync/devices`
- `GET /api/v1/sync/pull`
- `POST /api/v1/sync/push`
- `GET /api/v1/sync/conflicts`
- `POST /api/v1/sync/conflicts/{conflictId}/resolve`
- `GET /api/v1/sync/tombstones`
- `POST /api/v1/sync/qr-card/sign`
- `POST /api/v1/sync/qr-card/verify`

## 7. FHIR / HL7 / Standards Mappings
- Maps internal domain identifiers using UUIDv7 and standard clinical terminologies (LOINC, SNOMED CT, ICD-10/11, RxNorm, UCUM) where applicable.
- See `docs/API_CONFORMANCE_REPORT.md` for verified FHIR R4, HL7 v2.5.1, IHE, and DICOMweb conformance boundaries.

## 8. Authorization Model
- Zero `or isAuthenticated()` fallbacks on clinical or patient-data endpoints.
- Method-level `@PreAuthorize` guards detected in this module:
- `hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_audit:read') or hasAuthority('PERM_facility:manage')`
- `hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_facility:manage')`
- `hasAuthority('PERM_facility:manage') or hasAuthority('PERM_audit:read')`
- `hasAuthority('PERM_facility:manage') or hasAuthority('PERM_system:admin')`
- `hasAuthority('PERM_patient:read') or hasAuthority('PERM_chw:visit:write') or hasAuthority('PERM_facility:manage')`
- `hasAuthority('PERM_patient:read') or hasAuthority('PERM_hie:export') or hasAuthority('PERM_chw:visit:write')`
- `hasAuthority('PERM_patient:read') or hasAuthority('PERM_hie:export') or hasAuthority('PERM_encounter:sign')`
- `hasAuthority('PERM_patient:write') or hasAuthority('PERM_chw:visit:write') or hasAuthority('PERM_encounter:sign')`

## 9. Tenant Scope
- All clinical and operational records are scoped by `facility_id` via `FacilityContext`, JWT `facility_id` validation, Hibernate `facilityFilter`, and PostgreSQL Row-Level Security (`PostgresRlsSessionAspect`).

## 10. Audit Events
`OFFLINE_CONFLICT_RESOLVE`, `OFFLINE_SYNC_PUSH`

## 11. Configuration
- Configured via standard Spring Boot properties and environment variables documented in `.env.example` and `docs/PRODUCTION_RUNBOOK.md`.
- No hardcoded production secrets; `ProductionSecretsStartupValidator` fails closed in production profiles.

## 12. Deployment Requirements
- Java 25 (JDK 25), Spring Boot 4.0, PostgreSQL 16+ (primary) or MSSQL / MySQL, and OIDC/Keycloak issuer.
- Include `com.opeshealth.healthcore:healthcore-starter-offline-sync:1.0.0` with `healthcore-bom`.

## 13. Failure Behavior
- Fails closed (`AccessDeniedException` / `SecurityException` / `IllegalStateException`) on missing tenant context, unauthorized access, invalid clinical state transitions, or missing mandatory safety context.
- Never returns fabricated `200 OK` responses for unsupported operations.

## 14. Clinical Validation Status
- **Status:** PRE-PRODUCTION / ENGINEERING BUILD.
- Conflict resolution rules for clinical records require clinical governance review.

## 15. Test Evidence
- Module test classes:
- `OfflineSyncAndRevocationTest.java`
- Verification command (JDK 25 CI): `mvn -B -ntp test -pl healthcore-starter-offline-sync -am`

## 16. Known Limitations
- Live Maven/JUnit/Testcontainers execution must be verified in JDK 25 + Docker CI.
- Refer to `docs/KNOWN_LIMITATIONS_REGISTER.md` for full platform-level caveats and go-live gates.
