# Spring Boot Health Core (EMR / EHR / HIE + PostgreSQL)

Pure **Java 21 + Spring Boot 3.4 + PostgreSQL 16 + Flyway + HAPI FHIR R4** reusable backend modules for Electronic Medical Records (EMR), Electronic Health Records (EHR), and Health Information Exchange (HIE).

## Reusable Modules

1. **Multi-Facility Tenant Context (`com.healthcore.tenant`)**
   - `FacilityContext`, `FacilityInterceptor`, `WebTenantConfig`
   - Extracts `X-Facility-Id` per HTTP request via `ThreadLocal` with automatic cleanup.

2. **Facility Registry (`com.healthcore.facility`)**
   - `FacilityEntity`, `FacilityRepository`, `FacilityController`
   - Register and query clinics, hospitals, labs, and HIE nodes.

3. **Master Patient Index — MPI (`com.healthcore.mpi`)**
   - `PatientEntity`, `PatientIdentifierEntity`, `PatientRepository`, `PatientIdentifierRepository`, `MpiService`
   - Enterprise MPI IDs, National IDs, facility MRNs, and PostgreSQL `pg_trgm` fuzzy candidate matching.

4. **HIE Consent & Emergency Break-the-Glass (`com.healthcore.consent`)**
   - `PatientConsentEntity`, `ConsentRepository`, `ConsentEnforcementService`
   - Enforces active facility-scoped or network-wide sharing consent with `X-Break-Glass-Reason` emergency audit override.

5. **Immutable Clinical Audit Trail (`com.healthcore.audit`)**
   - `@ClinicalAudit`, `ClinicalAuditAspect`, `AuditWriter`, `AuditLogEntity`, `AuditLogRepository`
   - Declarative AOP auditing using `Propagation.REQUIRES_NEW` so denied and successful accesses commit reliably.

6. **EMR Encounters & Observations (`com.healthcore.emr`)**
   - `EncounterEntity` (PostgreSQL `jsonb` SOAP notes + `text[]` ICD-10 diagnosis codes)
   - `ObservationEntity` (LOINC-coded vitals/labs)
   - `EncounterService` atomically stores encounters and queues an HIE FHIR Transaction Bundle in the same DB transaction.

7. **Allergies & e-Prescribing (`com.healthcore.rx`)**
   - `PatientAllergyEntity`, `PrescriptionEntity`, `PrescriptionService`, `PrescriptionController`
   - Checks active patient allergies before issuing prescriptions unless a clinical override reason is recorded.

8. **HIE Interoperability & Outbox Relay (`com.healthcore.hie`)**
   - `FhirR4Mapper` (HAPI FHIR R4 `Patient`, `Encounter`, `Observation`, and `Bundle`)
   - `HieOutboxRelayWorker` (`FOR UPDATE SKIP LOCKED` polling, `X-Idempotency-Key`, exponential backoff, dead-letter queue).

## Running Locally

```bash
docker compose up -d
mvn test
mvn spring-boot:run
```
