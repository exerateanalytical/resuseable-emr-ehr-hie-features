# Appointments & Resource Scheduling Starter (`healthcore-starter-scheduling`)

## 1. Purpose
Manages provider/room/equipment calendars, appointment types, overlap/conflict detection, recurring series, check-in queues, waitlists, reschedule/cancellation, and FHIR Schedule/Appointment projections.

## 2. Implemented Capabilities
- Spring Boot 4.0 auto-configured starter (`AutoConfiguration.imports` + `@EnableJpaRepositories(considerNestedRepositories = true)`).
- Tenant-scoped domain services, JPA entities, and secured REST/standards endpoints.
- Cross-database Liquibase schema support (PostgreSQL, Microsoft SQL Server, MySQL).

## 3. Explicitly Unsupported Capabilities
Does not include live SMS/WhatsApp telecom carrier credentials (uses outbox/notification hooks).

## 4. Domain Model
- `AppointmentTypeEntity` (`hc_appointment_types`)
- `AppointmentWaitlistEntity` (`hc_appointment_waitlist`)
- `ResourceCalendarEntity` (`hc_resource_calendars`)
- `ScheduledAppointmentEntity` (`hc_scheduled_appointments`)

## 5. Database Migrations
- `db/changelog/scheduling/001-scheduling.xml`

## 6. REST APIs
- `POST /api/v1/scheduling/calendars`
- `GET /api/v1/scheduling/calendars`
- `POST /api/v1/scheduling/appointment-types`
- `GET /api/v1/scheduling/appointment-types`
- `POST /api/v1/scheduling/appointments`
- `POST /api/v1/scheduling/appointments/recurring`
- `GET /api/v1/scheduling/appointments`
- `POST /api/v1/scheduling/appointments/{id}/check-in`
- `POST /api/v1/scheduling/appointments/{id}/no-show`
- `PUT /api/v1/scheduling/appointments/{id}/reschedule`
- `POST /api/v1/scheduling/appointments/{id}/cancel`
- `POST /api/v1/scheduling/waitlist`
- `GET /api/v1/scheduling/fhir/Appointment`
- `GET /api/v1/scheduling/fhir/Schedule`

## 7. FHIR / HL7 / Standards Mappings
- Maps internal domain identifiers using UUIDv7 and standard clinical terminologies (LOINC, SNOMED CT, ICD-10/11, RxNorm, UCUM) where applicable.
- See `docs/API_CONFORMANCE_REPORT.md` for verified FHIR R4, HL7 v2.5.1, IHE, and DICOMweb conformance boundaries.

## 8. Authorization Model
- Zero `or isAuthenticated()` fallbacks on clinical or patient-data endpoints.
- Method-level `@PreAuthorize` guards detected in this module:
- `hasAuthority('PERM_facility:manage') or hasAuthority('PERM_system:admin') or hasAuthority('PERM_encounter:sign')`
- `hasAuthority('PERM_facility:manage') or hasAuthority('PERM_system:admin')`
- `hasAuthority('PERM_patient:read') or hasAuthority('PERM_facility:manage')`
- `hasAuthority('PERM_patient:read')`
- `hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_triage:write')`
- `hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:sign')`
- `hasAuthority('PERM_patient:write') or hasAuthority('PERM_triage:write')`

## 9. Tenant Scope
- All clinical and operational records are scoped by `facility_id` via `FacilityContext`, JWT `facility_id` validation, Hibernate `facilityFilter`, and PostgreSQL Row-Level Security (`PostgresRlsSessionAspect`).

## 10. Audit Events
`SCHEDULING_BOOK_APPOINTMENT`, `SCHEDULING_BOOK_RECURRING_SERIES`, `SCHEDULING_CANCEL`, `SCHEDULING_CHECK_IN`, `SCHEDULING_CREATE_CALENDAR`, `SCHEDULING_MARK_NO_SHOW`, `SCHEDULING_RESCHEDULE`

## 11. Configuration
- Configured via standard Spring Boot properties and environment variables documented in `.env.example` and `docs/PRODUCTION_RUNBOOK.md`.
- No hardcoded production secrets; `ProductionSecretsStartupValidator` fails closed in production profiles.

## 12. Deployment Requirements
- Java 25 (JDK 25), Spring Boot 4.0, PostgreSQL 16+ (primary) or MSSQL / MySQL, and OIDC/Keycloak issuer.
- Include `com.opeshealth.healthcore:healthcore-starter-scheduling:1.0.0` with `healthcore-bom`.

## 13. Failure Behavior
- Fails closed (`AccessDeniedException` / `SecurityException` / `IllegalStateException`) on missing tenant context, unauthorized access, invalid clinical state transitions, or missing mandatory safety context.
- Never returns fabricated `200 OK` responses for unsupported operations.

## 14. Clinical Validation Status
- **Status:** PRE-PRODUCTION / ENGINEERING BUILD.
- Not applicable (administrative scheduling workflow).

## 15. Test Evidence
- Module test classes:
- Covered via `healthcore-quickstart-host` integration/safety suites.
- Verification command (JDK 25 CI): `mvn -B -ntp test -pl healthcore-starter-scheduling -am`

## 16. Known Limitations
- Live Maven/JUnit/Testcontainers execution must be verified in JDK 25 + Docker CI.
- Refer to `docs/KNOWN_LIMITATIONS_REGISTER.md` for full platform-level caveats and go-live gates.
