# Production Runbook — Opes HealthCore HIS Platform

## 1. Pre-Deployment Checklist (Mandatory Go-Live Gate)
- [ ] Active Spring profiles include `prod` and target DB profile (`postgres`, `mssql`, or `mysql`).
- [ ] `dev-seed` profile is **NOT** active (`ProductionSecretsStartupValidator` will refuse startup if active in `prod`).
- [ ] `healthcore.emr.credentials.allow-unregistered-in-dev` is `false`.
- [ ] All secrets injected via secret manager / environment variables (minimum 32-char cryptographic keys; zero default placeholders):
  - `HEALTHCORE_DB_URL`, `HEALTHCORE_DB_USERNAME`, `HEALTHCORE_DB_PASSWORD`
  - `HEALTHCORE_PII_ENCRYPTION_KEY`, `HEALTHCORE_BLIND_INDEX_HMAC_KEY`
  - `HEALTHCORE_OIDC_ISSUER_URI`, `HEALTHCORE_OIDC_JWK_SET_URI`
  - `HEALTHCORE_WEBHOOK_SECRET`, `HEALTHCORE_GEMINI_API_KEY` (if AI enabled)
- [ ] PostgreSQL RLS policies applied and verified with a non-superuser runtime DB role (`healthcore.postgres.rls.enabled=true`).
- [ ] External PACS (Orthanc / DCM4CHEE) configured if binary DICOMweb STOW-RS/WADO-RS is enabled (`healthcore.hie.dicom.pacs-enabled=true`).
- [ ] Clean backup + restore drill completed and verified.

## 2. Startup & Health Verification
1. Launch the packaged Spring Boot 4.0 host on Java 25:
   ```bash
   java -jar healthcore-quickstart-host/target/healthcore-quickstart-host-1.0.0.jar \
     --spring.profiles.active=prod,postgres,keycloak
   ```
2. Verify readiness and liveness probes:
   - `GET /actuator/health/liveness` → `200 UP`
   - `GET /actuator/health/readiness` → `200 UP` (confirms DB connectivity & Liquibase completion)
3. Verify tenant governance status (`GET /api/v1/governance/facilities`) with an authenticated `PERM_facility:manage` token.

## 3. Emergency Operational Controls
- **Freeze a Compromised or Decommissioned Facility:**
  - Transition facility lifecycle to `READ_ONLY` (blocks all mutating API writes with `503`) or `SUSPENDED` (blocks all requests with `403`) via `PATCH /api/v1/governance/facilities/{facilityId}/lifecycle`.
- **Disable Clinical AI Immediately Across All Pods:**
  - Flip durable tenant AI policy kill-switch via `ClinicalAiGovernanceService.setTenantAiEnabled(facilityId, false)` (persisted in `TenantAiPolicyRepository`, enforced across all replicas).
- **Replay Failed External Integrations (PACS / HIE / DHIS2):**
  - Inspect quarantined messages via `/api/v1/ops/dlq` after upstream recovery and trigger controlled replay.

## 4. Rollback Procedure
1. Stop application pods and place ingress in maintenance mode.
2. Restore the pre-release database backup snapshot (do not run ad-hoc destructive DDL manually).
3. Redeploy the previously signed release container/JAR artifact and verify `/actuator/health/readiness` and critical clinical read/write smoke tests.
