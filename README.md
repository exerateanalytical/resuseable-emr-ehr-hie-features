# Opes HealthCore SDK (`com.opeshealth.healthcore`)

**Author / Principal Architect:** Jude Nshome — **Opes Health Systems**  
**Email:** `nshome@opesware.com`  
**Website:** [https://www.opeshealthsystems.com](https://www.opeshealthsystems.com)  
**Location:** Douala, Cameroon  

---

## Overview

**Opes HealthCore** is **not** a single-purpose end-user application — it is a **100% Pure Java 25 + Spring Boot 4.0** (plus **Kotlin/Compose Multiplatform** for Android, iPhone & iPad) reusable modular starter SDK. It eliminates ~90% of engineering effort when building:

- **Full Hospital EMR / EHR** (10-stage workflow: Appointments -> ESI Triage -> LOINC Vitals & NEWS2 -> Immutable SOAP Notes -> CDSS-gated CPOE -> Admissions/Ward Beds -> 5-Rights eMAR -> Discharge -> CarePlans -> Referrals)
- **Standalone Pharmacy Information System (PIS)** (`healthcore-starter-pharmacy-lis` FEFO batch picking, expiry quarantine, controlled substance dual-witness)
- **Standalone Diagnostic Lab Information System (LIS)** (`healthcore-starter-pharmacy-lis` barcode accession, Westgard QC gate, reflex & panic escalation, ASTM E1394)
- **Specialized Clinic Systems** (`Dental`, `Eye/Ophthalmology`, `Cardiac`, `Pediatrics`, `Dermatology` via `SpecialtyPackSpi`)
- **Regional / National HIE** (`healthcore-starter-hie-interop`: FHIR R4 IPS `$summary`, Bulk NDJSON `$export`, IHE PIXm/PDQm/MHD/ATNA, HL7 v2.5.1, DICOMweb, CDA R2)
- **Universal Health Care (UHC / CBHI), CHW, Home Care & IDSR Disease Watch** (Malaria, HIV, Hepatitis, TB, Cholera, VHF + DHIS2 ADX + MTN MoMo / Orange Money)
- **Offline-First Android, iPhone & iPad Companion Suite** (`healthcore-mobile-kmp`: SQLite/SQLCipher + HLC + `sync_seq` + Ed25519 QR IPS Health Card + iPad 3-Column Cockpit with Stylus canvas)

> **Strict Technology Guarantee:** 0% TypeScript / JavaScript / Node.js. Maven Enforcer + ArchUnit verify zero TypeScript artifacts.

---

## Module Architecture

| Module | Purpose |
| :--- | :--- |
| `healthcore-bom` | Centralized Bill of Materials (Spring Boot 4.0, Java 25, HAPI FHIR 8, Liquibase, Resilience4j, Caffeine, PG/MSSQL/MySQL) |
| `healthcore-persistence-core` | RFC 9562 UUIDv7, CQRS contracts, Transactional Outbox (`SKIP LOCKED` & MSSQL `READPAST`), SQL `IN` chunking, deadlock retries |
| `healthcore-security-audit` | Keycloak/Auth0 JWT + SMART on FHIR v2, 18-role RBAC, ABAC sensitivity (`HIV`/`PSYCH`/`VIP`) + Break-the-Glass, SHA-256 hash-chained audit, AES-256-GCM PII vault |
| `healthcore-starter-terminology-cdss` | LOINC, SNOMED CT (`hc_concept_closure` IS-A), ICD-10/11, RxNorm/ATC, UCUM, CDS Hooks 2.0 (DDI, Allergy class, Renal, Pediatric dosing, NEWS2) |
| `healthcore-starter-mpi` | Master Patient Index Golden Record, pure-Java Phonetic + Fellegi-Sunter deduplication, reversible merge, FHIR R4 `/Patient` |
| `healthcore-starter-emr-clinical` | Complete 10-stage clinical workflow + Dental, Eye, Cardiac, Pediatrics & Dermatology specialty packs |
| `healthcore-starter-pharmacy-lis` | Independent PIS (FEFO stock/dispensing) & LIS (accession, Westgard QC, panic alerts) |
| `healthcore-starter-hie-interop` | FHIR R4 IPS `$summary` & `$export`, HL7v2 (`MSH-10` dedup), ASTM E1394, DICOMweb (`QIDO-RS`/`WADO-RS`), CDA R2 |
| `healthcore-starter-uhc-billing` | Integer minor-unit double-entry ledger, CBHI / *Mutuelles de Santé* claims, MTN MoMo & Orange Money HMAC webhooks |
| `healthcore-starter-public-health` | CHW households/visits (MUAC/ANC/iCCM), Home Care, WHO EPI immunizations, IDSR Outbreak Watch & DHIS2 ADX XML |
| `healthcore-starter-hospital-ops` | Blood Bank (ISBT-128 + 2-person verify), OT (WHO Checklist gate), Telemedicine, 2°C–8°C Cold-Chain IoT, CRVS, Workforce license gate, HIPAA Safe-Harbor De-ID |
| `healthcore-starter-offline-sync` | Hybrid Logical Clock (`HLC`), `sync_seq` delta pull/push, clinical conflict queue, Ed25519 QR IPS Health Card |
| `healthcore-starter-templates-dashboards` | **608 bilingual (EN/FR) hospital document/report templates** + OpenHTMLtoPDF renderer, 7 CQRS dashboards, Facility Onboarding, Bulk CSV & DR snapshots |
| `healthcore-starter-api-product` | OpenAPI 3.1 contract, API keys, sliding-window rate limiter (`429 Retry-After`), HMAC webhook signer |
| `healthcore-quickstart-host` | Reference `<1 hour` Spring Boot 4.0 host, Liquibase master changelog, Keycloak realm, Windows WinSW service, ArchUnit & Testcontainers multi-DB suite |
| `healthcore-mobile-kmp` | Pure Kotlin + Compose Multiplatform offline-first Clinician (Phone + iPad Cockpit), Patient QR Card, and CHW Field apps |

---

## Build a New Healthcare System in < 1 Hour

### 1. Start Multi-DB & Keycloak Infrastructure
```bash
# PostgreSQL 16 + Keycloak 26 (pre-loaded with 18 HealthcareRoles)
docker compose up -d

# Or include Windows-compatible SQL Server 2022 & MySQL 8.4:
docker compose --profile all-dbs up -d
```

### 2. Compose Only the Modules You Need
Add `healthcore-bom` to `<dependencyManagement>` and pick only your target starters:
- **Standalone Pharmacy System:** `healthcore-persistence-core` + `healthcore-security-audit` + `healthcore-starter-terminology-cdss` + `healthcore-starter-mpi` + `healthcore-starter-pharmacy-lis` + `healthcore-starter-uhc-billing`
- **Standalone Eye Clinic:** above + `healthcore-starter-emr-clinical` (`FacilityProfile.EYE_CLINIC`)
- **Full Hospital / HIE:** use `healthcore-quickstart-host` directly!

### 3. Build, Test & Run (Java 25 Required)
```bash
# Compile & run unit + ArchUnit governance tests
mvn clean test

# Run Testcontainers multi-DB Liquibase parity tests (PostgreSQL + MSSQL + MySQL)
mvn test -Dgroups=multidb-it

# Launch on PostgreSQL + Keycloak (Port 3000)
mvn -pl healthcore-quickstart-host spring-boot:run -Dspring-boot.run.profiles=postgres,keycloak

# Launch on Windows SQL Server
mvn -pl healthcore-quickstart-host spring-boot:run -Dspring-boot.run.profiles=mssql,keycloak

# Launch on MySQL 8.4
mvn -pl healthcore-quickstart-host spring-boot:run -Dspring-boot.run.profiles=mysql,keycloak
```

### 4. Windows Server Service Deployment
Use `healthcore-quickstart-host/windows/install-service.ps1` with `WinSW` to run Opes HealthCore natively as a Windows Server background service connected to SQL Server.

### 5. Offline-First Mobile & iPad Suite (Kotlin Multiplatform)
```bash
cd healthcore-mobile-kmp
./gradlew :healthcore-mobile-sdk:build
```

OpenAPI 3.1 Swagger UI is served at `http://localhost:3000/swagger-ui.html`.
