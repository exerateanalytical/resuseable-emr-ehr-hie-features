# Outpatient and Specialty Clinic EMR — HealthCore SDK consumer

**Status: PRE-PRODUCTION, not clinically certified.** This Java 25/Spring Boot 4 application is a reference composition of the HealthCore EMR capability pack; it is not a complete physician-clinic product.

## Implemented and verified vs intended

The `IndependentWorkflowIT` source now exercises a real Spring application context with PostgreSQL 16 Testcontainers, Liquibase, two encounters for a returning synthetic patient, triage, measured vital signs, clinician-signed SOAP notes, an immutable signed-note correction through an addendum, missing-data safety and facility/credential denial. **It has not yet been executed successfully on JDK 25 CI**, and no full HTTP/OIDC verification is claimed.

**Important module-isolation limitation:** the `healthcore-pack-emr` POM excludes an explicit hospital-ops or mortuary dependency, but the underlying `healthcore-starter-emr-clinical` currently also scans inpatient/ward, medication-administration and referral entities. The pack master now includes both `emr/001` and `emr/002` migrations to avoid a Hibernate schema-validation failure. Therefore **do not claim zero inpatient tables** or strict outpatient entity isolation. A future separate outpatient-domain starter must extract this shared model safely.

## Developer prerequisites and independent test

JDK 25, Maven 3.9+, Docker/Testcontainers and PostgreSQL 16 image access. Use **only synthetic records**.

```bash
# From repository root, after the full Maven reactor has been repaired
mvn -B -ntp -fae -DskipTests install

# Test this example, not only the shared quickstart host
mvn -B -ntp -f examples/outpatient-emr/pom.xml clean verify -DskipITs=false
```

The `WorkflowSmokeTest` checks **classpath presence only**. The new `IndependentWorkflowIT` requires an actual database, migrations, domain services, and fresh JDBC connection assertions. The test supplies a fail-closed JWT decoder and a synthetic licensed-practitioner fixture for domain verification. It **does not authenticate a real patient or clinician via HTTP**, which remains a mandatory independent security gate.

The active facility is derived from verified authentication context in production. An arbitrary `X-Facility-Id` header is not authorization. The refactored clinical service rejects mismatches on encounter/triage/vitals/note-signing **and** facility-scopes appointments, orders, admissions, eMAR and specialty records. A practitioner credential verifier is now mandatory for signed clinical actions; missing verifier configuration **fails closed** instead of silently allowing prescribing. Not every legacy clinical method has yet been audited (including nursing handover/referral paths); no global tenant-isolation certification is claimed.

## Current clinical flow

1. Start an ambulatory encounter for a patient and authenticated facility.
2. Triage with a valid ESI 1–5 level, complaint and accountable clinician.
3. Record actual measured vital signs **plus supplemental-oxygen status (true/false, nullable before assessment) and ACVPU consciousness (A, C, V, P or U)**. The migration `emr/003-emr-news2-observation-context.xml` adds these nullable columns without defaulting unknown observations to normal. Missing essential measurements, oxygen status or ACVPU **must not** save a fabricated NEWS2 score of zero; the clinician receives an incomplete-observation alert.
4. Sign a SOAP note with an approved practitioner credential. A signed note cannot be overwritten; addenda retain parent and version history.
5. Create another encounter for a returning patient without changing their patient identifier.
6. Verify encounter, triage, vitals and signed-note state across fresh PostgreSQL connections, including foreign-facility and invalid-data rejections.

## NEWS2 clinical boundaries

This starter currently applies **adult NEWS2 Scale 1** as a preliminary clinical decision-support calculation. Both room-air status and alert consciousness must be **explicitly recorded**; missing values leave `news2_score` as `null`, never normal zero. The service rejects unsupported ACVPU codes rather than scoring them as confusion.

A value of zero means the **documented** inputs meet the modeled score thresholds; it does not certify medical stability. Adult Scale 2 (prescribed 88–92% oxygen target), pediatric PEWS, obstetric scoring, clinical escalation policies, calibrated devices and bedside validation are **not complete** and need physician/nurse clinical approval before care use.

## Disposition and referral safety (shared clinical starter)

The shared clinical starter now requires an authorized facility and a patient-matching encounter when creating or closing referrals. It preserves the first completed referral outcome on exact retries and rejects conflicting replacement outcomes. Inpatient discharge requires an active facility, matching encounter and owned occupied bed. A repeat discharge returns the original admission without freeing a bed that may already have been reassigned or generating another care plan and discharge event.

SBAR handovers, fluid intake/output and medication-reconciliation records require the active facility and an active, matching admission. A clinical co-signature requires a **PENDING_COSIGN** note and a credentialed attending physician; signed notes cannot be co-signed a second time.

**Not yet verified:** These protections have source-level JUnit regression coverage but no executed Java 25 or PostgreSQL report on this branch. Discharge readiness (unresolved critical findings, pending orders, medication reconciliation, medical decision-making, document signature) still needs cross-module E2E verification and clinical sign-off. Transactional outbox delivery and concurrent bed allocation require database/queue reliability tests before any release claim.

## Still required before the app can be called complete

- MPI record search, consent, provisional emergency identity, duplicate resolution, and appointment/check-in queue as real end-to-end scenarios.
- Nurse-to-doctor handoff, financial policy, insurer clearance, emergency payment override and auditable cashier queue.
- Clinical CPOE for laboratory, imaging and pharmacy with parallel department task creation, outbox/inbox delivery, patient-specific result matching and critical-value clinician acknowledgment.
- Disposition, admission/referral/discharge documents, follow-up scheduling, printable clinical forms and operational reports.
- Full live OIDC/SMART authorization, verified practitioner license source, tenant restrictions on **every** clinical operation (not just the four guarded paths), correct NEWS2 oxygen/consciousness/Scale 2 input handling, and independent medical review.
- Repeat/restart/idempotency, API contracts, database backup/restore, pack dependency isolation, 11-consumer release gate and fully green CI.

See [standalone acceptance matrix](../../docs/verification/EXAMPLE_WORKFLOW_ACCEPTANCE_MATRIX.md) and [patient-journey specification](../../docs/workflows/PATIENT_JOURNEY_MASTER_SPEC.md).
