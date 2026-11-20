/*
 * =============================================================================
 * Opes HealthCore SDK — Programmatic README Workflow Verification Service
 * =============================================================================
 */
package com.opeshealth.healthcore.testing;

import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.DraftAttestationStatus;
import com.opeshealth.healthcore.analytics.service.HospitalAnalyticsService;
import com.opeshealth.healthcore.emergency.service.EmergencyNursingService;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService;
import com.opeshealth.healthcore.portal.service.PatientPortalService;
import com.opeshealth.healthcore.radiology.service.RadiologyRisService;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService;
import com.opeshealth.healthcore.starters.BloodBankStarter;
import com.opeshealth.healthcore.starters.SurgeryOtStarter;
import com.opeshealth.healthcore.testing.HealthCoreDataFixture.ConsoleSeedSummary;
import com.opeshealth.healthcore.testing.HealthCoreDataFixture.SeededHospitalFixtureResult;
import com.opeshealth.healthcore.testing.data.IsoHl7StandardSampleData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/**
 * Programmatically seeds databases via {@link HealthCoreDataFixture} and immediately
 * triggers the documented core README workflows across the Reference Hospital consoles
 * and backend starters, logging structured {@code PASS}/{@code FAIL} telemetry for every
 * workflow step.
 *
 * <p>All domain-specific verification logic is isolated inside lazily-linked static helper
 * classes so consumers verifying a single starter or isolated capability pack never incur
 * {@link NoClassDefFoundError} for unrelated modules.</p>
 */
public class WorkflowVerificationService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowVerificationService.class);

    private final HealthCoreDataFixture fixture;
    private final RadiologyRisService radiologyService;
    private final EmergencyNursingService emergencyNursingService;
    private final UnifiedOrdersResultsService ordersResultsService;
    private final BloodBankStarter.BloodBankHemovigilanceService bloodBankService;
    private final SurgeryOtStarter.SurgicalPerioperativeService surgeryService;
    private final PatientPortalService portalService;
    private final IntegrationOpsService integrationOpsService;
    private final ClinicalAiGovernanceService aiGovernanceService;
    private final HospitalAnalyticsService analyticsService;
    private final RevenueCodingClaimsService revenueClaimsService;

    private WorkflowVerificationService(Builder b) {
        this.fixture = Objects.requireNonNull(b.fixture, "HealthCoreDataFixture is required");
        this.radiologyService = b.radiologyService;
        this.emergencyNursingService = b.emergencyNursingService;
        this.ordersResultsService = b.ordersResultsService;
        this.bloodBankService = b.bloodBankService;
        this.surgeryService = b.surgeryService;
        this.portalService = b.portalService;
        this.integrationOpsService = b.integrationOpsService;
        this.aiGovernanceService = b.aiGovernanceService;
        this.analyticsService = b.analyticsService;
        this.revenueClaimsService = b.revenueClaimsService;
    }

    public static Builder builder(HealthCoreDataFixture fixture) {
        return new Builder(fixture);
    }

    /**
     * Runs {@link HealthCoreDataFixture#seedAllReferenceConsolesAndStarters()} and then
     * programmatically triggers and verifies every documented core README workflow for
     * all wired consoles and starters, logging success or failure for each.
     */
    public WorkflowVerificationReport seedAndVerifyAllCoreReadmeWorkflows() {
        Instant startedAt = Instant.now();
        log.info("[WORKFLOW-VERIFY] Starting HealthCoreDataFixture full ISO/HL7 database seed...");
        SeededHospitalFixtureResult seeded = fixture.seedAllReferenceConsolesAndStarters();
        UUID facilityId = seeded.facilityId();
        UUID impPatientId = seeded.inpatientPatientId();
        UUID ambPatientId = seeded.ambulatoryPatientId();
        UUID encounterId = seeded.inpatientEncounterId();

        List<WorkflowExecutionResult> results = new ArrayList<>();

        if (radiologyService != null) {
            results.add(executeStep(
                    "radiology-ris",
                    "healthcore-starter-radiology/README.md",
                    facilityId,
                    () -> RadiologyVerifier.verify(radiologyService, seeded.consoleSummaries().get("radiology"))));
        }
        if (emergencyNursingService != null) {
            results.add(executeStep(
                    "emergency-nursing",
                    "healthcore-starter-emergency-nursing/README.md",
                    facilityId,
                    () -> EmergencyNursingVerifier.verify(emergencyNursingService, seeded.consoleSummaries().get("emergency-nursing"))));
        }
        if (ordersResultsService != null) {
            results.add(executeStep(
                    "laboratory-lis",
                    "healthcore-starter-orders-results/README.md",
                    facilityId,
                    () -> LaboratoryVerifier.verify(ordersResultsService, facilityId, impPatientId, seeded.consoleSummaries().get("laboratory"))));
        }
        if (bloodBankService != null || surgeryService != null) {
            results.add(executeStep(
                    "blood-bank-ot",
                    "healthcore-starter-blood-bank/README.md + healthcore-starter-surgery-ot/README.md",
                    facilityId,
                    () -> BloodBankOtVerifier.verify(bloodBankService, surgeryService, facilityId, seeded.consoleSummaries().get("blood-ot"))));
        }
        if (portalService != null) {
            results.add(executeStep(
                    "patient-portal",
                    "healthcore-starter-patient-portal/README.md",
                    facilityId,
                    () -> PatientPortalVerifier.verify(portalService, facilityId, ambPatientId, seeded.consoleSummaries().get("portal"))));
        }
        if (integrationOpsService != null) {
            results.add(executeStep(
                    "integration-ops",
                    "healthcore-starter-integration-ops/README.md",
                    facilityId,
                    () -> IntegrationOpsVerifier.verify(integrationOpsService, seeded.consoleSummaries().get("integration-ops"))));
        }
        if (aiGovernanceService != null) {
            results.add(executeStep(
                    "clinical-ai",
                    "healthcore-starter-clinical-ai/README.md",
                    facilityId,
                    () -> ClinicalAiVerifier.verify(aiGovernanceService, facilityId, seeded.consoleSummaries().get("clinical-ai"))));
        }
        if (analyticsService != null) {
            results.add(executeStep(
                    "analytics-quality",
                    "healthcore-starter-analytics/README.md",
                    facilityId,
                    () -> AnalyticsVerifier.verify(analyticsService, seeded.consoleSummaries().get("analytics"))));
        }
        if (revenueClaimsService != null) {
            results.add(executeStep(
                    "revenue-coding-claims",
                    "healthcore-starter-revenue-coding-claims/README.md",
                    facilityId,
                    () -> RevenueCodingClaimsVerifier.verify(revenueClaimsService, seeded.consoleSummaries().get("revenue-coding-claims"))));
        }

        WorkflowVerificationReport report = new WorkflowVerificationReport(
                facilityId, startedAt, Instant.now(), Collections.unmodifiableList(results));
        logSummary(report);
        return report;
    }

    /**
     * Seeds a single console or starter in strict isolation using {@link HealthCoreDataFixture}
     * and programmatically verifies its documented README workflow without touching unrelated modules.
     */
    public WorkflowExecutionResult seedAndVerifyIsolatedWorkflow(
            String workflowKey, UUID facilityId, UUID patientId, UUID encounterId) {
        Objects.requireNonNull(workflowKey, "workflowKey is required");
        Objects.requireNonNull(facilityId, "facilityId is required");

        return switch (workflowKey) {
            case "radiology-ris" -> executeStep(workflowKey, "healthcore-starter-radiology/README.md", facilityId, () -> {
                ConsoleSeedSummary s = fixture.seedRadiologyConsole(facilityId, patientId, encounterId);
                return RadiologyVerifier.verify(radiologyService, s);
            });
            case "emergency-nursing" -> executeStep(workflowKey, "healthcore-starter-emergency-nursing/README.md", facilityId, () -> {
                ConsoleSeedSummary s = fixture.seedEmergencyNursingConsole(facilityId, patientId, encounterId);
                return EmergencyNursingVerifier.verify(emergencyNursingService, s);
            });
            case "laboratory-lis" -> executeStep(workflowKey, "healthcore-starter-orders-results/README.md", facilityId, () -> {
                ConsoleSeedSummary s = fixture.seedLaboratoryConsole(facilityId, patientId, encounterId);
                return LaboratoryVerifier.verify(ordersResultsService, facilityId, patientId, s);
            });
            case "blood-bank-ot" -> executeStep(workflowKey, "healthcore-starter-blood-bank/README.md + healthcore-starter-surgery-ot/README.md", facilityId, () -> {
                ConsoleSeedSummary s = fixture.seedBloodBankAndOtConsole(facilityId, patientId);
                return BloodBankOtVerifier.verify(bloodBankService, surgeryService, facilityId, s);
            });
            case "patient-portal" -> executeStep(workflowKey, "healthcore-starter-patient-portal/README.md", facilityId, () -> {
                ConsoleSeedSummary s = fixture.seedPatientPortalConsole(facilityId, patientId);
                return PatientPortalVerifier.verify(portalService, facilityId, patientId, s);
            });
            case "integration-ops" -> executeStep(workflowKey, "healthcore-starter-integration-ops/README.md", facilityId, () -> {
                ConsoleSeedSummary s = fixture.seedIntegrationOpsStarter(facilityId);
                return IntegrationOpsVerifier.verify(integrationOpsService, s);
            });
            case "clinical-ai" -> executeStep(workflowKey, "healthcore-starter-clinical-ai/README.md", facilityId, () -> {
                ConsoleSeedSummary s = fixture.seedClinicalAiConsole(facilityId, patientId);
                return ClinicalAiVerifier.verify(aiGovernanceService, facilityId, s);
            });
            case "analytics-quality" -> executeStep(workflowKey, "healthcore-starter-analytics/README.md", facilityId, () -> {
                ConsoleSeedSummary s = fixture.seedAnalyticsConsole(facilityId);
                return AnalyticsVerifier.verify(analyticsService, s);
            });
            case "revenue-coding-claims" -> executeStep(workflowKey, "healthcore-starter-revenue-coding-claims/README.md", facilityId, () -> {
                ConsoleSeedSummary s = fixture.seedRevenueCodingClaimsStarter(facilityId, patientId, encounterId);
                return RevenueCodingClaimsVerifier.verify(revenueClaimsService, s);
            });
            default -> {
                String msg = "Unknown workflowKey '" + workflowKey + "'";
                log.error("[WORKFLOW-VERIFY] [FAIL] workflow={} reason={}", workflowKey, msg);
                yield new WorkflowExecutionResult(workflowKey, "N/A", false, 0L, msg);
            }
        };
    }

    private WorkflowExecutionResult executeStep(
            String workflowKey, String readmeRef, UUID facilityId, Supplier<String> verifier) {
        Instant stepStart = Instant.now();
        try {
            String detail = fixture.withinFacility(facilityId, verifier);
            long elapsedMs = Duration.between(stepStart, Instant.now()).toMillis();
            log.info("[WORKFLOW-VERIFY] [PASS] workflow={} readme={} durationMs={} detail={}",
                    workflowKey, readmeRef, elapsedMs, detail);
            return new WorkflowExecutionResult(workflowKey, readmeRef, true, elapsedMs, detail);
        } catch (Exception ex) {
            long elapsedMs = Duration.between(stepStart, Instant.now()).toMillis();
            String reason = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            log.error("[WORKFLOW-VERIFY] [FAIL] workflow={} readme={} durationMs={} error={}",
                    workflowKey, readmeRef, elapsedMs, reason, ex);
            return new WorkflowExecutionResult(workflowKey, readmeRef, false, elapsedMs, reason);
        }
    }

    private void logSummary(WorkflowVerificationReport report) {
        if (report.allPassed()) {
            log.info("[WORKFLOW-VERIFY] ALL {} README workflows PASSED for facility={}",
                    report.passedCount(), report.facilityId());
        } else {
            log.error("[WORKFLOW-VERIFY] Workflow verification COMPLETED WITH FAILURES: passed={} failed={} facility={}",
                    report.passedCount(), report.failedCount(), report.facilityId());
        }
    }

    // =========================================================================
    // LAZILY-LINKED README WORKFLOW VERIFIERS (ISOLATION-SAFE)
    // =========================================================================

    private static final class RadiologyVerifier {
        static String verify(RadiologyRisService service, ConsoleSeedSummary seeded) {
            Objects.requireNonNull(service, "radiologyService must be wired");
            if (seeded == null || seeded.operationsExecuted() < 2) {
                throw new IllegalStateException("Radiology fixture seed incomplete");
            }
            var completedExams = service.listWorklist("COMPLETED");
            var unackCritical = service.listUnacknowledgedCriticalFindings();
            if (completedExams == null || completedExams.isEmpty()) {
                throw new IllegalStateException("Expected >= 1 COMPLETED DICOM worklist exam after seeding");
            }
            if (unackCritical != null && !unackCritical.isEmpty()) {
                throw new IllegalStateException("Expected 0 unacknowledged critical radiology findings after read-back");
            }
            return "Verified " + completedExams.size() + " COMPLETED DICOM exam(s) and 0 pending critical read-backs";
        }
    }

    private static final class EmergencyNursingVerifier {
        static String verify(EmergencyNursingService service, ConsoleSeedSummary seeded) {
            Objects.requireNonNull(service, "emergencyNursingService must be wired");
            if (seeded == null || seeded.operationsExecuted() < 3) {
                throw new IllegalStateException("Emergency/Nursing fixture seed incomplete");
            }
            var edBoard = service.getActiveEdBoard();
            var escalations = service.listActiveEscalations();
            if (edBoard == null || edBoard.isEmpty()) {
                throw new IllegalStateException("Expected >= 1 active ESI-2 ED triage visit on board");
            }
            if (escalations == null || escalations.isEmpty()) {
                throw new IllegalStateException("Expected >= 1 active NEWS2 clinical escalation observation");
            }
            return "Verified " + edBoard.size() + " active ED visit(s) and " + escalations.size() + " NEWS2 escalation(s)";
        }
    }

    private static final class LaboratoryVerifier {
        static String verify(UnifiedOrdersResultsService service, UUID facilityId, UUID patientId, ConsoleSeedSummary seeded) {
            Objects.requireNonNull(service, "ordersResultsService must be wired");
            if (seeded == null || seeded.operationsExecuted() < 3) {
                throw new IllegalStateException("Laboratory fixture seed incomplete");
            }
            var orders = service.listPatientOrders(facilityId, patientId);
            var results = service.listPatientResults(facilityId, patientId);
            if (orders == null || orders.isEmpty()) {
                throw new IllegalStateException("Expected >= 1 LOINC laboratory order for seeded patient");
            }
            if (results == null || results.isEmpty()) {
                throw new IllegalStateException("Expected >= 1 verified LOINC critical potassium result");
            }
            return "Verified " + orders.size() + " patient order(s) and " + results.size() + " verified LOINC result(s)";
        }
    }

    private static final class BloodBankOtVerifier {
        static String verify(BloodBankStarter.BloodBankHemovigilanceService bloodSvc,
                             SurgeryOtStarter.SurgicalPerioperativeService surgerySvc,
                             UUID facilityId, ConsoleSeedSummary seeded) {
            if (seeded == null || seeded.operationsExecuted() < 2) {
                throw new IllegalStateException("Blood Bank / OT fixture seed incomplete");
            }
            StringBuilder sb = new StringBuilder();
            if (bloodSvc != null) {
                var eval = bloodSvc.evaluateCompatibility("O-", "AB+", "PRBC");
                if (eval == null || !eval.compatible()) {
                    throw new IllegalStateException("Expected O- PRBC universal donor compatibility with AB+ recipient");
                }
                sb.append("ISBT-128 ABO/Rh compatibility=PASS");
            }
            if (surgerySvc != null) {
                UUID scheduleId = seeded.createdIds().size() >= 2 ? seeded.createdIds().get(1) : UUID.randomUUID();
                var pacu = surgerySvc.evaluateAndRecordPacuAldreteScore(
                        new SurgeryOtStarter.PacuAldreteAssessmentRequest(scheduleId, 2, 2, 2, 2, 2, 60, "ASA_II"));
                if (pacu == null || !pacu.eligibleForWardDischarge()) {
                    throw new IllegalStateException("Expected Modified Aldrete 10/10 PACU ward discharge eligibility");
                }
                if (!sb.isEmpty()) sb.append("; ");
                sb.append("PACU Aldrete=").append(pacu.totalAldreteScore()).append("/10 (DISCHARGE_ELIGIBLE)");
            }
            return sb.toString();
        }
    }

    private static final class PatientPortalVerifier {
        static String verify(PatientPortalService service, UUID facilityId, UUID patientId, ConsoleSeedSummary seeded) {
            Objects.requireNonNull(service, "portalService must be wired");
            if (seeded == null || seeded.operationsExecuted() < 3) {
                throw new IllegalStateException("Patient Portal fixture seed incomplete");
            }
            String handle = "portal-" + patientId;
            var threads = service.listPatientThreads(facilityId, handle, patientId);
            if (threads == null || threads.isEmpty()) {
                throw new IllegalStateException("Expected >= 1 patient portal care-team messaging thread");
            }
            return "Verified enrolled portal account '" + handle + "' with " + threads.size() + " care-team thread(s)";
        }
    }

    private static final class IntegrationOpsVerifier {
        static String verify(IntegrationOpsService service, ConsoleSeedSummary seeded) {
            Objects.requireNonNull(service, "integrationOpsService must be wired");
            if (seeded == null || seeded.operationsExecuted() < 4) {
                throw new IllegalStateException("Integration Ops fixture seed incomplete");
            }
            var partners = service.listPartners();
            var summaries = service.getPartnerHealthSummaries();
            if (partners == null || partners.size() < 2) {
                throw new IllegalStateException("Expected >= 2 registered FHIR R4 & HL7 v2.5.1 partner profiles");
            }
            if (summaries == null || summaries.isEmpty()) {
                throw new IllegalStateException("Expected non-empty partner health & DLQ telemetry summaries");
            }
            return "Verified " + partners.size() + " trading partner(s) and " + summaries.size() + " health/DLQ summary row(s)";
        }
    }

    private static final class ClinicalAiVerifier {
        static String verify(ClinicalAiGovernanceService service, UUID facilityId, ConsoleSeedSummary seeded) {
            Objects.requireNonNull(service, "aiGovernanceService must be wired");
            if (seeded == null || seeded.operationsExecuted() < 2) {
                throw new IllegalStateException("Clinical AI fixture seed incomplete");
            }
            if (!service.isTenantAiEnabled(facilityId)) {
                throw new IllegalStateException("Expected tenant AI governance policy enabled for seeded facility");
            }
            var approvedDrafts = service.listDrafts(facilityId, DraftAttestationStatus.APPROVED);
            if (approvedDrafts == null || approvedDrafts.isEmpty()) {
                throw new IllegalStateException("Expected >= 1 clinician-attested APPROVED Clinical AI draft");
            }
            return "Verified tenant AI opt-in enabled and " + approvedDrafts.size() + " clinician-attested draft(s)";
        }
    }

    private static final class AnalyticsVerifier {
        static String verify(HospitalAnalyticsService service, ConsoleSeedSummary seeded) {
            Objects.requireNonNull(service, "analyticsService must be wired");
            if (seeded == null || seeded.operationsExecuted() < 2) {
                throw new IllegalStateException("Analytics fixture seed incomplete");
            }
            var measures = service.listQualityMeasures();
            var exports = service.listCohortExports();
            if (measures == null || measures.isEmpty()) {
                throw new IllegalStateException("Expected >= 1 recorded CMS eCQM quality measure snapshot");
            }
            if (exports == null || exports.isEmpty()) {
                throw new IllegalStateException("Expected >= 1 DPO-approved k-anonymized cohort export request");
            }
            return "Verified " + measures.size() + " eCQM measure(s) and " + exports.size() + " cohort export(s)";
        }
    }

    private static final class RevenueCodingClaimsVerifier {
        static String verify(RevenueCodingClaimsService service, ConsoleSeedSummary seeded) {
            Objects.requireNonNull(service, "revenueClaimsService must be wired");
            if (seeded == null || seeded.operationsExecuted() < 5) {
                throw new IllegalStateException("Revenue Coding & Claims fixture seed incomplete");
            }
            long quotedXaf = service.quoteTariffPrice(IsoHl7StandardSampleData.PAYER_CNPS_UHC, "CONS-CARDIO");
            var summary = service.getClaimsSummary();
            if (quotedXaf <= 0L) {
                throw new IllegalStateException("Expected positive ISO 4217 XAF tariff quote for CONS-CARDIO");
            }
            if (summary == null || summary.totalClaims() < 1) {
                throw new IllegalStateException("Expected >= 1 submitted & ERA-reconciled payer claim");
            }
            return "Verified XAF tariff quote=" + quotedXaf + " and claimsSummary(total=" + summary.totalClaims()
                    + ", paid=" + summary.paidClaims() + ")";
        }
    }

    // =========================================================================
    // RESULT & REPORT RECORDS + FLUENT BUILDER
    // =========================================================================

    public record WorkflowExecutionResult(
            String workflowKey,
            String readmeReference,
            boolean passed,
            long durationMs,
            String detail
    ) {}

    public record WorkflowVerificationReport(
            UUID facilityId,
            Instant startedAt,
            Instant completedAt,
            List<WorkflowExecutionResult> results
    ) {
        public long passedCount() {
            return results.stream().filter(WorkflowExecutionResult::passed).count();
        }

        public long failedCount() {
            return results.stream().filter(r -> !r.passed()).count();
        }

        public boolean allPassed() {
            return !results.isEmpty() && failedCount() == 0;
        }

        public void assertAllPassed() {
            if (!allPassed()) {
                List<String> failures = results.stream()
                        .filter(r -> !r.passed())
                        .map(r -> r.workflowKey() + " -> " + r.detail())
                        .toList();
                throw new IllegalStateException("README workflow verification failed: " + failures);
            }
        }

        public String toMarkdownSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("## HealthCore README Workflow Verification Report\n\n");
            sb.append("- **Facility**: `").append(facilityId).append("`\n");
            sb.append("- **Result**: **").append(passedCount()).append("/").append(results.size())
                    .append(" PASSED** (").append(failedCount()).append(" failed)\n\n");
            sb.append("| Workflow | README Reference | Status | Duration (ms) | Detail |\n");
            sb.append("|---|---|---|---:|---|\n");
            for (WorkflowExecutionResult r : results) {
                sb.append("| `").append(r.workflowKey()).append("` | `").append(r.readmeReference())
                        .append("` | ").append(r.passed() ? "PASS" : "FAIL")
                        .append(" | ").append(r.durationMs())
                        .append(" | ").append(r.detail()).append(" |\n");
            }
            return sb.toString();
        }
    }

    public static final class Builder {
        private final HealthCoreDataFixture fixture;
        private RadiologyRisService radiologyService;
        private EmergencyNursingService emergencyNursingService;
        private UnifiedOrdersResultsService ordersResultsService;
        private BloodBankStarter.BloodBankHemovigilanceService bloodBankService;
        private SurgeryOtStarter.SurgicalPerioperativeService surgeryService;
        private PatientPortalService portalService;
        private IntegrationOpsService integrationOpsService;
        private ClinicalAiGovernanceService aiGovernanceService;
        private HospitalAnalyticsService analyticsService;
        private RevenueCodingClaimsService revenueClaimsService;

        private Builder(HealthCoreDataFixture fixture) {
            this.fixture = fixture;
        }

        public Builder radiologyService(RadiologyRisService s) { this.radiologyService = s; return this; }
        public Builder emergencyNursingService(EmergencyNursingService s) { this.emergencyNursingService = s; return this; }
        public Builder ordersResultsService(UnifiedOrdersResultsService s) { this.ordersResultsService = s; return this; }
        public Builder bloodBankService(BloodBankStarter.BloodBankHemovigilanceService s) { this.bloodBankService = s; return this; }
        public Builder surgeryService(SurgeryOtStarter.SurgicalPerioperativeService s) { this.surgeryService = s; return this; }
        public Builder portalService(PatientPortalService s) { this.portalService = s; return this; }
        public Builder integrationOpsService(IntegrationOpsService s) { this.integrationOpsService = s; return this; }
        public Builder aiGovernanceService(ClinicalAiGovernanceService s) { this.aiGovernanceService = s; return this; }
        public Builder analyticsService(HospitalAnalyticsService s) { this.analyticsService = s; return this; }
        public Builder revenueClaimsService(RevenueCodingClaimsService s) { this.revenueClaimsService = s; return this; }

        public WorkflowVerificationService build() {
            return new WorkflowVerificationService(this);
        }
    }
}
