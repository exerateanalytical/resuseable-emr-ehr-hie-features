# Standalone Radiology / Imaging Center — HealthCore SDK consumer

**Status: PRE-PRODUCTION.** This is a Java 25 + Spring Boot 4 reference consumer, not an approved clinical RIS, PACS, DICOM device interface or diagnostic service.

## Problem addressed and implemented capabilities

The radiology SDK models a facility-scoped imaging order/worklist, DICOM `StudyInstanceUID`, modality performed procedure steps (MPPS), structured radiology reports, signed amendments and critical finding acknowledgment.

The standalone example explicitly depends on `healthcore-starter-diagnostics-core` and `healthcore-starter-radiology`, not hospital HIS, EMR, inpatient or standalone laboratory LIS packs. The dependency tree and Java 25 real application boot must confirm transitive isolation before claiming full separation.

Current workflow checks:

1. Authorized synthetic ordering clinician requests a CT/MR/US/etc exam for a patient and visit. The worklist has a unique accession and valid DICOM UID.
2. Exam enters **`SCHEDULED`**, then an accountable imaging technologist moves MPPS to **`IN_PROGRESS`** before **`COMPLETED`**; direct completion and negative image counts are rejected.
3. An authorized synthetic radiologist documents findings and impression only after completed acquisition. A `FINAL` report is persisted with study and patient references.
4. Critical findings remain unacknowledged until a separate clinician supplies documented read-back. Repeated exact acknowledgment preserves the original clinician and timestamp; conflicting replay is rejected.
5. Amendments produce **new** `AMENDED` report rows only when referencing an existing signed report for the same patient, worklist and DICOM study. The previous signed report remains unchanged.
6. Read and write operations scope records to the authenticated facility. A cross-facility critical-result acknowledgement must be denied.

## Run real standalone verification

Requires JDK 25, Maven 3.9+, Docker/Testcontainers, PostgreSQL 16 image access and **synthetic test records only**.

```bash
# From repository root — install the selected SDK modules plus BOM
mvn -B -ntp -pl healthcore-bom,healthcore-starter-radiology,healthcore-starter-diagnostics-core -am clean install -DskipITs=true

# Start standalone Spring Boot app + PostgreSQL Testcontainers + Liquibase
mvn -B -ntp -f examples/radiology-center/pom.xml clean verify -DskipITs=false
```

`IndependentWorkflowIT` tests a real PostgreSQL schema, persists an urgent CT worklist/MPPS, signed critical report and read-back, checks duplicate acknowledgement and cross-facility denial, and tests same-patient amendment lineage versus a wrong-patient study. SQL assertions open fresh JDBC connections. `WorkflowSmokeTest` alone is classpath-only and never counts as workflow evidence.

The dedicated `.github/workflows/standalone-radiology-branch-assurance.yml` CI gate requires a non-skipped Failsafe report with at least two executed successful integration tests. **Do not report a green result until the exact candidate commit passes that workflow.**

## Blocking work before actual medical use

- Real DICOM MWL, MPPS and DICOMweb connectivity to imaging devices/PACS; scanner protocol, UID cross-system collision, reconciliation and downtime recovery tests
- Clinician and radiologist real identity, OIDC/JWT/RBAC, authorization for ordering/report signing, licensed user privileges, correct patient consent and second-read workflow
- Secure image access, retention, offline recovery, report templates/printables and radiology audit provenance
- Verified critical alert delivery to a responsible clinician and documented human response; a clinician ID string and a read-back note are **not independently verified delivery or clinical authorization**
- Billing, payer authorization, emergency exception governance, patient-specific ordering context, link to external EMR/clinician inbox and follow-up closure
- Actual multi-user concurrency, rollback, idempotent DICOM ingestion, amended report distribution, signed digital reports and clinician/security acceptance

No real patient data or clinical deployment until these workflows are independently reviewed and complete.
