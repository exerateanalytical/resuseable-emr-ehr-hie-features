/*
 * =============================================================================
 * Opes HealthCore SDK — ClinicalCapacityPredictor (Patient Flow, Staffing & Audit)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.ai.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.TextGenerationResult;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.AiCapabilityType;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.ClinicalAiDraftRecord;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway.RedactedClinicalPayload;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway.RedactionContext;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Reusable Spring component inside {@code healthcore-starter-clinical-ai} that forecasts
 * ER/clinic patient flow, bed occupancy, and shift-level staff scheduling requirements from
 * historical admission patterns and optional HL7 FHIR R4 {@code Encounter} histories, with full
 * tamper-evident audit trail integration ({@link ClinicalAuditService} + SHA-256 governance ledger).
 *
 * <p><strong>Operational Safety, Privacy &amp; Audit Guarantees:</strong></p>
 * <ul>
 *   <li><strong>Full Audit Trail Integration:</strong> Emits correlated pre-forecast, completion,
 *       denial, and failure audit entries into {@link ClinicalAuditService} (SHA-256 hash-chained
 *       database ledger) and maintains a facility-scoped audit timeline with cryptographic input
 *       and output hashes (zero raw PHI ever persisted in audit entries).</li>
 *   <li><strong>Mandatory Safe-Harbor PHI Redaction:</strong> Scrubs all 18 HIPAA Safe-Harbor
 *       identifier categories across optional FHIR {@code Encounter} payloads and free-text surge
 *       notes before invoking Gemini.</li>
 *   <li><strong>Deterministic Capacity &amp; Staffing Baseline:</strong> Computes arrival trends,
 *       high-acuity share, boarding pressure, surge tier ({@code GREEN_NORMAL} to
 *       {@code RED_CRITICAL_DIVERSION}), and nurse/physician shift deltas locally prior to AI
 *       schedule synthesis.</li>
 *   <li><strong>Human-in-the-Loop Governance:</strong> Registers every AI-generated staffing
 *       schedule as {@code DRAFT_PENDING_ATTESTATION} in {@link ClinicalAiGovernanceService} for
 *       nursing/operations leadership approval and propagates upstream errors cleanly.</li>
 * </ul>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Component
public class ClinicalCapacityPredictor {

    public enum CareUnitType {
        EMERGENCY_DEPARTMENT,
        OUTPATIENT_CLINIC,
        INPATIENT_WARD,
        ICU_CRITICAL_CARE,
        MATERNITY_OB,
        SURGICAL_DAY_UNIT
    }

    public enum CapacityHorizon {
        NEXT_24_HOURS(24),
        NEXT_72_HOURS(72),
        NEXT_7_DAYS(168);

        private final int hours;

        CapacityHorizon(int hours) {
            this.hours = hours;
        }

        public int hours() {
            return hours;
        }
    }

    public enum SurgeLevel {
        GREEN_NORMAL,
        YELLOW_ELEVATED,
        ORANGE_HIGH_SURGE,
        RED_CRITICAL_DIVERSION
    }

    public record HistoricalAdmissionWindow(
            String windowStartIso,
            String dayOfWeek,
            String shiftName,
            int arrivalsCount,
            int admissionsCount,
            int dischargesCount,
            int boardingCount,
            double averageWaitMinutes,
            int highAcuityEsi1To2Count,
            double bedOccupancyPercent
    ) {}

    public record FacilityCapacityBaseline(
            int totalLicensedBeds,
            int availableBeds,
            int currentRnsOnDuty,
            int currentPhysiciansOnDuty,
            int currentSupportStaffOnDuty,
            double targetPatientsPerNurse,
            List<String> localSurgeEvents
    ) {}

    public record CapacityForecastRequest(
            UUID facilityId,
            String requestedByActorId,
            CareUnitType careUnit,
            CapacityHorizon horizon,
            FacilityCapacityBaseline currentBaseline,
            List<HistoricalAdmissionWindow> historicalWindows,
            List<String> historicalFhirEncountersJson
    ) {}

    public record ShiftStaffingRecommendation(
            String shiftName,
            int projectedPatientLoad,
            int recommendedRns,
            int recommendedPhysicians,
            int recommendedSupportStaff,
            int additionalRnsNeeded,
            int additionalPhysiciansNeeded
    ) {}

    public record DeterministicCapacityMetrics(
            int windowsAnalyzed,
            int fhirEncountersIngested,
            double meanArrivalsPerWindow,
            int peakArrivalsPerWindow,
            double arrivalTrendSlopePercent,
            int projectedPeakArrivals,
            double projectedOccupancyPercent,
            double highAcuitySharePercent,
            double averageWaitMinutes,
            int peakBoardingCount,
            SurgeLevel surgeLevel,
            List<ShiftStaffingRecommendation> shiftStaffingBaselines
    ) {}

    public record RedactedCapacityContext(
            RedactedClinicalPayload redactedPayload,
            DeterministicCapacityMetrics deterministicMetrics,
            int totalFhirResourcesProcessed
    ) {}

    public record CapacityAuditEvent(
            UUID auditEventId,
            String correlationId,
            UUID facilityId,
            String actorId,
            String action,
            CareUnitType careUnit,
            String outcome,
            String inputDigestSha256,
            String outputDigestSha256,
            int phiRedactions,
            String detail,
            Instant recordedAt
    ) {}

    public record CapacityForecastResult(
            UUID draftId,
            String attestationStatus,
            String auditCorrelationId,
            CareUnitType careUnit,
            CapacityHorizon horizon,
            String modelId,
            DeterministicCapacityMetrics deterministicBaseline,
            JsonNode aiCapacityAndStaffingPlan,
            int totalPhiRedactions,
            Map<String, Integer> redactionCountsByCategory,
            long latencyMs
    ) {}

    private final GeminiClinicalApiClient geminiClient;
    private final PhiRedactionGateway phiRedactionGateway;
    private final ClinicalAiGovernanceService governanceService;
    private final ClinicalAuditService clinicalAuditService;
    private final ObjectMapper objectMapper;
    private final List<CapacityAuditEvent> auditHistory = new CopyOnWriteArrayList<>();
    private final List<Consumer<CapacityAuditEvent>> auditListeners = new CopyOnWriteArrayList<>();

    @Autowired
    public ClinicalCapacityPredictor(
            GeminiClinicalApiClient geminiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService governanceService,
            ObjectProvider<ClinicalAuditService> clinicalAuditServiceProvider) {
        this(geminiClient, phiRedactionGateway, governanceService,
                clinicalAuditServiceProvider != null ? clinicalAuditServiceProvider.getIfAvailable() : null);
    }

    public ClinicalCapacityPredictor(
            GeminiClinicalApiClient geminiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService governanceService) {
        this(geminiClient, phiRedactionGateway, governanceService, (ClinicalAuditService) null);
    }

    public ClinicalCapacityPredictor(
            GeminiClinicalApiClient geminiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService governanceService,
            ClinicalAuditService clinicalAuditService) {
        this.geminiClient = Objects.requireNonNull(geminiClient, "GeminiClinicalApiClient is required");
        this.phiRedactionGateway = Objects.requireNonNull(phiRedactionGateway, "PhiRedactionGateway is required");
        this.governanceService = Objects.requireNonNull(governanceService, "ClinicalAiGovernanceService is required");
        this.clinicalAuditService = clinicalAuditService;
        this.objectMapper = geminiClient.getObjectMapper();
    }

    public void registerAuditListener(Consumer<CapacityAuditEvent> listener) {
        if (listener != null) {
            auditListeners.add(listener);
        }
    }

    /**
     * Returns the immutable chronological audit trail of capacity forecasts and baseline checks
     * recorded for a healthcare facility (newest first).
     */
    public List<CapacityAuditEvent> getAuditTrailForFacility(UUID facilityId) {
        return auditHistory.stream()
                .filter(e -> facilityId == null || facilityId.equals(e.facilityId()))
                .sorted(Comparator.comparing(CapacityAuditEvent::recordedAt).reversed())
                .toList();
    }

    /**
     * Forecasts ER/clinic patient flow and shift-level staff scheduling requirements using
     * historical admission patterns, optional FHIR Encounter histories, mandatory Safe-Harbor
     * PHI redaction, Gemini structured operational planning ({@code gemini-4-hydrogen}), and
     * full tamper-evident audit trail logging.
     */
    public CapacityForecastResult forecastPatientFlowAndStaffing(CapacityForecastRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("CapacityForecastRequest is required.");
        }
        String correlationId = UUID.randomUUID().toString();
        String actorId = (request.requestedByActorId() != null && !request.requestedByActorId().isBlank())
                ? request.requestedByActorId().trim() : "OPS_SCHEDULER_SYSTEM";
        CareUnitType careUnit = request.careUnit() != null ? request.careUnit() : CareUnitType.EMERGENCY_DEPARTMENT;
        CapacityHorizon horizon = request.horizon() != null ? request.horizon() : CapacityHorizon.NEXT_72_HOURS;

        try {
            governanceService.assertTenantAiAllowed(request.facilityId(), AiCapabilityType.CAPACITY_AND_STAFFING_FORECAST);
        } catch (RuntimeException ex) {
            recordAuditEvent(correlationId, request.facilityId(), actorId,
                    "CLINICAL_AI_CAPACITY_FORECAST_DENIED", careUnit, "DENIED",
                    null, null, 0, ex.getMessage());
            throw ex;
        }

        RedactedCapacityContext context = prepareRedactedCapacityContext(request);
        if (context.deterministicMetrics().windowsAnalyzed() == 0
                && context.deterministicMetrics().fhirEncountersIngested() == 0) {
            IllegalArgumentException invalid = new IllegalArgumentException(
                    "At least one historical admission window or FHIR Encounter resource is required.");
            recordAuditEvent(correlationId, request.facilityId(), actorId,
                    "CLINICAL_AI_CAPACITY_FORECAST_FAILED", careUnit, "FAILURE",
                    context.redactedPayload().redactedSha256Hex(), null,
                    context.redactedPayload().totalRedactions(), invalid.getMessage());
            throw invalid;
        }

        recordAuditEvent(correlationId, request.facilityId(), actorId,
                "CLINICAL_AI_CAPACITY_FORECAST_REQUESTED", careUnit, "IN_PROGRESS",
                context.redactedPayload().redactedSha256Hex(), null,
                context.redactedPayload().totalRedactions(),
                "horizon=" + horizon.name()
                        + " windows=" + context.deterministicMetrics().windowsAnalyzed()
                        + " surgeLevel=" + context.deterministicMetrics().surgeLevel());

        String systemInstruction = """
                You are a hospital operations research and clinical workforce capacity forecasting engine
                embedded inside an HL7 FHIR R4 EMR/EHR platform.
                Using ONLY the de-identified historical ER/clinic admission patterns, bed occupancy,
                boarding pressure, acuity mix, and deterministic staffing baselines provided:
                1. Forecast patient arrival flow, peak congestion windows, projected bed occupancy,
                   bottleneck stages (triage, lab/radiology turnaround, inpatient bed placement), and
                   surge/diversion triggers over the requested horizon.
                2. Produce a shift-by-shift staff scheduling plan (DAY, EVENING, NIGHT) detailing
                   recommended physicians, registered nurses, support staff, and float-pool/on-call
                   activation while respecting safe nurse-to-patient ratios.
                3. Provide concrete throughput mitigation playbooks (fast-track lane, discharge lounge,
                   bed-turnaround huddles).
                4. Never fabricate historical metrics absent from the input and preserve Safe-Harbor
                   placeholders verbatim. Output strictly valid JSON matching responseSchema.
                """;

        String prompt = "Care Unit: " + careUnit.name()
                + "\nForecast Horizon: " + horizon.name() + " (" + horizon.hours() + " hours)"
                + "\nDeterministic Surge Level: " + context.deterministicMetrics().surgeLevel()
                + "\n\nDe-identified Historical Admission & Capacity Digest:\n"
                + context.redactedPayload().redactedText();

        try {
            TextGenerationResult aiResult = geminiClient.generateClinicalContent(
                    systemInstruction,
                    prompt,
                    List.of(),
                    buildCapacityForecastSchema(),
                    "MEDIUM"
            );

            JsonNode parsedPlan = parseJsonSafely(aiResult.text());
            String outputJson = parsedPlan.toString();
            String outputSha256 = PhiRedactionGateway.sha256Hex(outputJson);

            ClinicalAiDraftRecord draft = governanceService.registerDraft(
                    request.facilityId(),
                    null,
                    AiCapabilityType.CAPACITY_AND_STAFFING_FORECAST,
                    aiResult.modelId(),
                    context.redactedPayload().redactedSha256Hex(),
                    context.redactedPayload().redactionCountsByCategory(),
                    outputJson,
                    aiResult.tokenUsage(),
                    aiResult.latencyMs(),
                    actorId
            );

            recordAuditEvent(correlationId, request.facilityId(), actorId,
                    "CLINICAL_AI_CAPACITY_FORECAST_COMPLETED", careUnit, "SUCCESS",
                    context.redactedPayload().redactedSha256Hex(), outputSha256,
                    context.redactedPayload().totalRedactions(),
                    "draftId=" + draft.draftId() + " model=" + aiResult.modelId()
                            + " surgeLevel=" + context.deterministicMetrics().surgeLevel()
                            + " latencyMs=" + aiResult.latencyMs());

            return new CapacityForecastResult(
                    draft.draftId(),
                    draft.status().name(),
                    correlationId,
                    careUnit,
                    horizon,
                    aiResult.modelId(),
                    context.deterministicMetrics(),
                    parsedPlan,
                    context.redactedPayload().totalRedactions(),
                    context.redactedPayload().redactionCountsByCategory(),
                    aiResult.latencyMs()
            );
        } catch (RuntimeException ex) {
            recordAuditEvent(correlationId, request.facilityId(), actorId,
                    "CLINICAL_AI_CAPACITY_FORECAST_FAILED", careUnit, "FAILURE",
                    context.redactedPayload().redactedSha256Hex(), null,
                    context.redactedPayload().totalRedactions(),
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
            throw ex;
        }
    }

    /**
     * Evaluates deterministic admission trends, surge level, and shift-level staffing deltas
     * locally while scrubbing any optional FHIR Encounter PHI and recording a baseline audit entry.
     */
    public RedactedCapacityContext analyzeCapacityBaselineWithAudit(CapacityForecastRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("CapacityForecastRequest is required.");
        }
        String correlationId = UUID.randomUUID().toString();
        String actorId = (request.requestedByActorId() != null && !request.requestedByActorId().isBlank())
                ? request.requestedByActorId().trim() : "OPS_SCHEDULER_SYSTEM";
        CareUnitType careUnit = request.careUnit() != null ? request.careUnit() : CareUnitType.EMERGENCY_DEPARTMENT;

        RedactedCapacityContext context = prepareRedactedCapacityContext(request);
        recordAuditEvent(correlationId, request.facilityId(), actorId,
                "CLINICAL_AI_CAPACITY_BASELINE_ANALYZED", careUnit, "SUCCESS",
                context.redactedPayload().redactedSha256Hex(), null,
                context.redactedPayload().totalRedactions(),
                "windows=" + context.deterministicMetrics().windowsAnalyzed()
                        + " fhirEncounters=" + context.deterministicMetrics().fhirEncountersIngested()
                        + " surgeLevel=" + context.deterministicMetrics().surgeLevel());
        return context;
    }

    /**
     * Unwraps optional historical FHIR Encounter payloads, harvests any embedded patient direct
     * identifiers, computes deterministic patient-flow and staffing baselines, and applies
     * mandatory HIPAA Safe-Harbor redaction across the entire operational digest.
     */
    public RedactedCapacityContext prepareRedactedCapacityContext(CapacityForecastRequest request) {
        List<JsonNode> fhirNodes = new ArrayList<>();
        List<String> supplementalTexts = new ArrayList<>();
        parseFhirInputList(request.historicalFhirEncountersJson(), fhirNodes, supplementalTexts);

        Set<String> harvestedNames = new LinkedHashSet<>();
        Set<String> harvestedIds = new LinkedHashSet<>();
        String harvestedDob = null;
        int fhirEncounterCount = 0;
        int fhirEmergencyCount = 0;

        for (JsonNode node : fhirNodes) {
            String type = node.path("resourceType").asText("UnknownResource");
            if ("Patient".equals(type) || "RelatedPerson".equals(type)) {
                harvestPatientIdentifiers(node, harvestedNames, harvestedIds);
                if (harvestedDob == null && node.hasNonNull("birthDate")) {
                    String dob = node.path("birthDate").asText("").trim();
                    if (!dob.isBlank()) {
                        harvestedDob = dob;
                    }
                }
            } else if ("Encounter".equals(type)) {
                fhirEncounterCount++;
                String cls = extractEncounterClassCode(node).toUpperCase(Locale.ROOT);
                if ("EMER".equals(cls) || "EMERGENCY".equals(cls)) {
                    fhirEmergencyCount++;
                }
            }
        }

        CareUnitType careUnit = request.careUnit() != null ? request.careUnit() : CareUnitType.EMERGENCY_DEPARTMENT;
        DeterministicCapacityMetrics metrics = computeDeterministicCapacityMetrics(
                careUnit,
                request.currentBaseline(),
                request.historicalWindows(),
                fhirEncounterCount,
                fhirEmergencyCount
        );

        StringBuilder digest = new StringBuilder();
        FacilityCapacityBaseline base = request.currentBaseline();
        if (base != null) {
            digest.append("=== CURRENT FACILITY CAPACITY BASELINE ===\n")
                    .append("unit=").append(careUnit.name())
                    .append(", licensedBeds=").append(base.totalLicensedBeds())
                    .append(", availableBeds=").append(base.availableBeds())
                    .append(", currentRNs=").append(base.currentRnsOnDuty())
                    .append(", currentPhysicians=").append(base.currentPhysiciansOnDuty())
                    .append(", currentSupportStaff=").append(base.currentSupportStaffOnDuty())
                    .append(", targetPatientsPerNurse=").append(resolveTargetRatio(careUnit, base)).append("\n");
            if (base.localSurgeEvents() != null && !base.localSurgeEvents().isEmpty()) {
                digest.append("localSurgeEvents=").append(String.join("; ", base.localSurgeEvents())).append("\n");
            }
        }

        List<HistoricalAdmissionWindow> windows = request.historicalWindows() != null
                ? request.historicalWindows() : List.of();
        if (!windows.isEmpty()) {
            digest.append("=== HISTORICAL ADMISSION WINDOWS ===\n");
            for (HistoricalAdmissionWindow w : windows) {
                digest.append("[Window] start=").append(nullSafe(w.windowStartIso(), "unspecified"))
                        .append(", day=").append(nullSafe(w.dayOfWeek(), "N/A"))
                        .append(", shift=").append(nullSafe(w.shiftName(), "DAY"))
                        .append(", arrivals=").append(w.arrivalsCount())
                        .append(", admissions=").append(w.admissionsCount())
                        .append(", discharges=").append(w.dischargesCount())
                        .append(", boarding=").append(w.boardingCount())
                        .append(", avgWaitMin=").append(String.format(Locale.ROOT, "%.1f", w.averageWaitMinutes()))
                        .append(", esi1To2=").append(w.highAcuityEsi1To2Count())
                        .append(", bedOccPct=").append(String.format(Locale.ROOT, "%.1f", w.bedOccupancyPercent()))
                        .append("\n");
            }
        }

        if (fhirEncounterCount > 0) {
            digest.append("=== AGGREGATED HISTORICAL FHIR ENCOUNTERS ===\n")
                    .append("totalEncounters=").append(fhirEncounterCount)
                    .append(", emergencyClassEncounters=").append(fhirEmergencyCount).append("\n");
        }
        for (String extra : supplementalTexts) {
            digest.append("[Supplemental Operational Note] ").append(extra).append("\n");
        }

        digest.append("=== DETERMINISTIC FLOW & STAFFING BASELINE ===\n")
                .append("meanArrivals=").append(String.format(Locale.ROOT, "%.1f", metrics.meanArrivalsPerWindow()))
                .append(", peakArrivals=").append(metrics.peakArrivalsPerWindow())
                .append(", arrivalTrendSlopePct=").append(String.format(Locale.ROOT, "%+.1f%%", metrics.arrivalTrendSlopePercent()))
                .append(", projectedPeakArrivals=").append(metrics.projectedPeakArrivals())
                .append(", projectedOccupancyPct=").append(String.format(Locale.ROOT, "%.1f%%", metrics.projectedOccupancyPercent()))
                .append(", highAcuitySharePct=").append(String.format(Locale.ROOT, "%.1f%%", metrics.highAcuitySharePercent()))
                .append(", peakBoarding=").append(metrics.peakBoardingCount())
                .append(", surgeLevel=").append(metrics.surgeLevel()).append("\n");
        for (ShiftStaffingRecommendation rec : metrics.shiftStaffingBaselines()) {
            digest.append("[ShiftBaseline] shift=").append(rec.shiftName())
                    .append(", projectedLoad=").append(rec.projectedPatientLoad())
                    .append(", recRNs=").append(rec.recommendedRns())
                    .append(" (delta ").append(String.format(Locale.ROOT, "%+d", rec.additionalRnsNeeded())).append(")")
                    .append(", recPhysicians=").append(rec.recommendedPhysicians())
                    .append(" (delta ").append(String.format(Locale.ROOT, "%+d", rec.additionalPhysiciansNeeded())).append(")")
                    .append(", recSupport=").append(rec.recommendedSupportStaff()).append("\n");
        }

        RedactionContext ctx = new RedactionContext(
                List.copyOf(harvestedNames),
                List.copyOf(harvestedIds),
                harvestedDob
        );
        RedactedClinicalPayload redacted = phiRedactionGateway.redact(digest.toString().trim(), ctx);

        return new RedactedCapacityContext(redacted, metrics, fhirNodes.size());
    }

    /**
     * Deterministically analyzes historical admission windows and facility baseline constraints to
     * compute arrival momentum, surge severity, and shift-level nurse/physician staffing baselines.
     */
    public DeterministicCapacityMetrics computeDeterministicCapacityMetrics(
            CareUnitType careUnit,
            FacilityCapacityBaseline baseline,
            List<HistoricalAdmissionWindow> historicalWindows,
            int fhirEncounterCount,
            int fhirEmergencyCount) {
        List<HistoricalAdmissionWindow> windows = historicalWindows != null ? historicalWindows : List.of();
        int n = windows.size();

        double meanArrivals = 0.0;
        int peakArrivals = 0;
        double slopePercent = 0.0;
        double avgWait = 0.0;
        int peakBoarding = 0;
        double highAcuityShare = 0.0;
        double maxObservedOccupancy = 0.0;

        Map<String, List<HistoricalAdmissionWindow>> byShift = new LinkedHashMap<>();
        byShift.put("DAY", new ArrayList<>());
        byShift.put("EVENING", new ArrayList<>());
        byShift.put("NIGHT", new ArrayList<>());

        if (n > 0) {
            int totalArrivals = 0;
            int totalHighAcuity = 0;
            double waitSum = 0.0;
            for (HistoricalAdmissionWindow w : windows) {
                totalArrivals += Math.max(0, w.arrivalsCount());
                totalHighAcuity += Math.max(0, w.highAcuityEsi1To2Count());
                peakArrivals = Math.max(peakArrivals, w.arrivalsCount());
                peakBoarding = Math.max(peakBoarding, w.boardingCount());
                maxObservedOccupancy = Math.max(maxObservedOccupancy, w.bedOccupancyPercent());
                waitSum += Math.max(0.0, w.averageWaitMinutes());
                String shiftKey = normalizeShiftKey(w.shiftName());
                byShift.computeIfAbsent(shiftKey, k -> new ArrayList<>()).add(w);
            }
            meanArrivals = (double) totalArrivals / n;
            avgWait = waitSum / n;
            highAcuityShare = totalArrivals > 0 ? (totalHighAcuity * 100.0) / totalArrivals : 0.0;

            if (n >= 2) {
                int split = n / 2;
                double firstHalfAvg = windows.subList(0, split).stream()
                        .mapToInt(HistoricalAdmissionWindow::arrivalsCount).average().orElse(0.0);
                double secondHalfAvg = windows.subList(split, n).stream()
                        .mapToInt(HistoricalAdmissionWindow::arrivalsCount).average().orElse(0.0);
                if (firstHalfAvg > 0.0) {
                    slopePercent = ((secondHalfAvg - firstHalfAvg) / firstHalfAvg) * 100.0;
                }
            }
        } else if (fhirEncounterCount > 0) {
            meanArrivals = fhirEncounterCount;
            peakArrivals = fhirEncounterCount;
            highAcuityShare = (fhirEmergencyCount * 100.0) / fhirEncounterCount;
        }

        double momentumFactor = 1.0 + Math.max(-0.20, Math.min(0.45, slopePercent / 100.0));
        int projectedPeakArrivals = (int) Math.ceil(Math.max(peakArrivals, meanArrivals) * momentumFactor);

        double baselineOccupancy = maxObservedOccupancy;
        if (baseline != null && baseline.totalLicensedBeds() > 0) {
            double currentOcc = ((baseline.totalLicensedBeds() - Math.max(0, baseline.availableBeds())) * 100.0)
                    / baseline.totalLicensedBeds();
            baselineOccupancy = Math.max(baselineOccupancy, currentOcc);
        }
        double projectedOccupancy = Math.min(125.0, baselineOccupancy * Math.max(1.0, momentumFactor));

        SurgeLevel surgeLevel;
        if (projectedOccupancy >= 95.0 || peakBoarding >= 10 || (projectedOccupancy >= 88.0 && slopePercent >= 20.0)) {
            surgeLevel = SurgeLevel.RED_CRITICAL_DIVERSION;
        } else if (projectedOccupancy >= 85.0 || peakBoarding >= 5 || slopePercent >= 15.0) {
            surgeLevel = SurgeLevel.ORANGE_HIGH_SURGE;
        } else if (projectedOccupancy >= 75.0 || avgWait >= 45.0 || slopePercent >= 8.0) {
            surgeLevel = SurgeLevel.YELLOW_ELEVATED;
        } else {
            surgeLevel = SurgeLevel.GREEN_NORMAL;
        }

        double targetPatientsPerNurse = resolveTargetRatio(careUnit, baseline);
        double targetPatientsPerPhysician = resolvePhysicianRatio(careUnit);
        int currentRns = baseline != null ? Math.max(0, baseline.currentRnsOnDuty()) : 0;
        int currentPhysicians = baseline != null ? Math.max(0, baseline.currentPhysiciansOnDuty()) : 0;

        List<ShiftStaffingRecommendation> shiftRecs = new ArrayList<>();
        for (Map.Entry<String, List<HistoricalAdmissionWindow>> entry : byShift.entrySet()) {
            String shift = entry.getKey();
            List<HistoricalAdmissionWindow> shiftWindows = entry.getValue();
            double shiftLoadBase = !shiftWindows.isEmpty()
                    ? shiftWindows.stream().mapToInt(w -> w.arrivalsCount() + w.boardingCount()).max().orElse(0)
                    : Math.max(1.0, meanArrivals * defaultShiftLoadMultiplier(shift));

            int projectedLoad = (int) Math.ceil(shiftLoadBase * Math.max(1.0, momentumFactor));
            double acuityMultiplier = highAcuityShare >= 25.0 ? 1.15 : 1.0;
            int recRns = Math.max(1, (int) Math.ceil((projectedLoad * acuityMultiplier) / targetPatientsPerNurse));
            int recPhysicians = Math.max(1, (int) Math.ceil((projectedLoad * acuityMultiplier) / targetPatientsPerPhysician));
            int recSupport = Math.max(1, (int) Math.ceil(recRns * 0.5));

            shiftRecs.add(new ShiftStaffingRecommendation(
                    shift,
                    projectedLoad,
                    recRns,
                    recPhysicians,
                    recSupport,
                    Math.max(0, recRns - currentRns),
                    Math.max(0, recPhysicians - currentPhysicians)
            ));
        }

        return new DeterministicCapacityMetrics(
                n,
                fhirEncounterCount,
                meanArrivals,
                peakArrivals,
                slopePercent,
                projectedPeakArrivals,
                projectedOccupancy,
                highAcuityShare,
                avgWait,
                peakBoarding,
                surgeLevel,
                Collections.unmodifiableList(shiftRecs)
        );
    }

    private void recordAuditEvent(
            String correlationId,
            UUID facilityId,
            String actorId,
            String action,
            CareUnitType careUnit,
            String outcome,
            String inputDigestSha256,
            String outputDigestSha256,
            int phiRedactions,
            String rawDetail) {
        String scrubbedDetail = rawDetail != null
                ? phiRedactionGateway.redact(rawDetail).redactedText() : null;

        CapacityAuditEvent event = new CapacityAuditEvent(
                UUID.randomUUID(),
                correlationId,
                facilityId,
                actorId,
                action,
                careUnit,
                outcome,
                inputDigestSha256,
                outputDigestSha256,
                phiRedactions,
                scrubbedDetail,
                Instant.now()
        );
        auditHistory.add(event);

        for (Consumer<CapacityAuditEvent> listener : auditListeners) {
            try {
                listener.accept(event);
            } catch (Exception ignored) {
                // Listener exceptions must never disrupt clinical operations
            }
        }

        if (clinicalAuditService != null) {
            try {
                String ledgerReason = "unit=" + careUnit.name()
                        + " phiRedacted=" + phiRedactions
                        + (inputDigestSha256 != null ? " inputSha256=" + inputDigestSha256 : "")
                        + (outputDigestSha256 != null ? " outputSha256=" + outputDigestSha256 : "")
                        + (scrubbedDetail != null ? " detail=" + scrubbedDetail : "");
                clinicalAuditService.recordEventWithCorrelation(
                        facilityId != null ? facilityId.toString() : "GLOBAL",
                        actorId,
                        "ROLE_CLINICAL_OPERATIONS_AI",
                        action,
                        "ClinicalCapacityForecast",
                        event.auditEventId().toString(),
                        null,
                        outcome,
                        ledgerReason,
                        "internal-capacity-ai-engine",
                        correlationId
                );
            } catch (Exception ignored) {
                // Never mask operational outcome if audit DB is unreachable in standalone mode
            }
        }
    }

    private double resolveTargetRatio(CareUnitType unit, FacilityCapacityBaseline baseline) {
        if (baseline != null && baseline.targetPatientsPerNurse() > 0.0) {
            return baseline.targetPatientsPerNurse();
        }
        return switch (unit) {
            case ICU_CRITICAL_CARE -> 2.0;
            case EMERGENCY_DEPARTMENT -> 4.0;
            case MATERNITY_OB -> 3.0;
            case SURGICAL_DAY_UNIT -> 4.0;
            case INPATIENT_WARD -> 5.0;
            case OUTPATIENT_CLINIC -> 8.0;
        };
    }

    private double resolvePhysicianRatio(CareUnitType unit) {
        return switch (unit) {
            case ICU_CRITICAL_CARE -> 8.0;
            case EMERGENCY_DEPARTMENT -> 10.0;
            case MATERNITY_OB -> 10.0;
            case SURGICAL_DAY_UNIT -> 12.0;
            case INPATIENT_WARD -> 14.0;
            case OUTPATIENT_CLINIC -> 18.0;
        };
    }

    private double defaultShiftLoadMultiplier(String shift) {
        return switch (shift) {
            case "DAY" -> 1.0;
            case "EVENING" -> 0.90;
            default -> 0.65;
        };
    }

    private String normalizeShiftKey(String shift) {
        if (shift == null || shift.isBlank()) {
            return "DAY";
        }
        String up = shift.trim().toUpperCase(Locale.ROOT);
        if (up.contains("NIGHT")) return "NIGHT";
        if (up.contains("EVEN") || up.contains("PM") || up.contains("SWING")) return "EVENING";
        return "DAY";
    }

    private String nullSafe(String val, String fallback) {
        return (val != null && !val.isBlank()) ? val.trim() : fallback;
    }

    private void parseFhirInputList(List<String> rawList, List<JsonNode> nodeSink, List<String> textSink) {
        if (rawList == null) {
            return;
        }
        for (String raw : rawList) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                JsonNode parsed = objectMapper.readTree(raw);
                flattenFhirNode(parsed, nodeSink);
            } catch (Exception ex) {
                textSink.add(raw.trim());
            }
        }
    }

    private void flattenFhirNode(JsonNode node, List<JsonNode> sink) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                flattenFhirNode(item, sink);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        if ("Bundle".equals(node.path("resourceType").asText("")) && node.path("entry").isArray()) {
            for (JsonNode entry : node.path("entry")) {
                if (entry.path("resource").isObject()) {
                    flattenFhirNode(entry.path("resource"), sink);
                }
            }
            return;
        }
        sink.add(node);
    }

    private void harvestPatientIdentifiers(JsonNode patientNode, Set<String> names, Set<String> ids) {
        if (patientNode.path("name").isArray()) {
            for (JsonNode hn : patientNode.path("name")) {
                String text = hn.path("text").asText("").trim();
                if (text.length() >= 2) {
                    names.add(text);
                }
                String family = hn.path("family").asText("").trim();
                List<String> givens = new ArrayList<>();
                if (hn.path("given").isArray()) {
                    for (JsonNode g : hn.path("given")) {
                        String gv = g.asText("").trim();
                        if (gv.length() >= 2) {
                            givens.add(gv);
                            names.add(gv);
                        }
                    }
                }
                if (family.length() >= 2) {
                    names.add(family);
                    if (!givens.isEmpty()) {
                        names.add(String.join(" ", givens) + " " + family);
                    }
                }
            }
        }
        if (patientNode.path("identifier").isArray()) {
            for (JsonNode id : patientNode.path("identifier")) {
                String v = id.path("value").asText("").trim();
                if (v.length() >= 3) {
                    ids.add(v);
                }
            }
        }
        if (patientNode.path("telecom").isArray()) {
            for (JsonNode tel : patientNode.path("telecom")) {
                String v = tel.path("value").asText("").trim();
                if (v.length() >= 4) {
                    ids.add(v);
                }
            }
        }
    }

    private String extractEncounterClassCode(JsonNode encounter) {
        JsonNode cls = encounter.path("class");
        if (cls.isObject()) {
            return cls.path("code").asText("");
        }
        if (cls.isArray() && !cls.isEmpty()) {
            return cls.get(0).path("coding").path(0).path("code").asText("");
        }
        return "";
    }

    private ObjectNode buildCapacityForecastSchema() {
        ObjectNode root = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode props = objectMapper.createObjectNode();

        ObjectNode flowObj = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode flowProps = objectMapper.createObjectNode();
        flowProps.set("projectedPeakVolume", objectMapper.createObjectNode().put("type", "INTEGER"));
        flowProps.set("projectedPeakWindow", objectMapper.createObjectNode().put("type", "STRING"));
        flowProps.set("projectedBedOccupancyPercent", objectMapper.createObjectNode().put("type", "NUMBER"));
        flowProps.set("surgeLevel", objectMapper.createObjectNode().put("type", "STRING"));
        flowProps.set("bottleneckStages", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        flowProps.set("diversionOrDecompressionTriggers", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        flowObj.set("properties", flowProps);
        props.set("patientFlowForecast", flowObj);

        ObjectNode shiftItem = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode shiftProps = objectMapper.createObjectNode();
        shiftProps.set("shiftName", objectMapper.createObjectNode().put("type", "STRING"));
        shiftProps.set("recommendedPhysicians", objectMapper.createObjectNode().put("type", "INTEGER"));
        shiftProps.set("recommendedNurses", objectMapper.createObjectNode().put("type", "INTEGER"));
        shiftProps.set("recommendedSupportStaff", objectMapper.createObjectNode().put("type", "INTEGER"));
        shiftProps.set("floatPoolOrOnCallActivation", objectMapper.createObjectNode().put("type", "STRING"));
        shiftProps.set("rationale", objectMapper.createObjectNode().put("type", "STRING"));
        shiftItem.set("properties", shiftProps);
        props.set("staffSchedulingPlan", objectMapper.createObjectNode().put("type", "ARRAY").set("items", shiftItem));

        props.set("throughputMitigationActions", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        props.set("operationsLeaderApprovalRequired", objectMapper.createObjectNode().put("type", "BOOLEAN"));

        root.set("properties", props);
        ArrayNode req = objectMapper.createArrayNode()
                .add("patientFlowForecast")
                .add("staffSchedulingPlan")
                .add("throughputMitigationActions")
                .add("operationsLeaderApprovalRequired");
        root.set("required", req);
        return root;
    }

    private JsonNode parseJsonSafely(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (Exception ex) {
            return objectMapper.createObjectNode().put("rawOutput", text);
        }
    }
}
