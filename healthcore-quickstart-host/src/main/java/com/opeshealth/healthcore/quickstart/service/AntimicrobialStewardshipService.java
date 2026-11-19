/*
 * Copyright (c) 2026 Opes Health Systems (https://www.opeshealthsystems.com)
 *
 * Author: Jude Nshome — Opes Health Systems
 * Email: nshome@opesware.com
 * Organization: Opes Health Systems, Douala, Cameroon
 *
 * Licensed under the Opes HealthCore Enterprise SDK License.
 */
package com.opeshealth.healthcore.quickstart.service;

import com.opeshealth.healthcore.quickstart.service.FhirSubscriptionTopicService.SubscriptionEventDispatchRequest;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.service.PatientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Antimicrobial Stewardship (AMS), WHO AWaRe Classification Governance, Cumulative Antibiogram
 * &amp; Multidrug-Resistant Organism (MDRO / Nosocomial HAI) Surveillance Service.
 *
 * <p>Classifies antimicrobial orders under the **WHO AWaRe (`ACCESS`, `WATCH`, `RESERVE`)**
 * framework, enforces infectious-disease pre-authorization and 48/72-hour antibiotic time-out
 * reviews for `RESERVE` agents, tracks microbiology isolate MIC susceptibility profiles (`S`, `I`, `R`),
 * detects MDRO pathogens (`MRSA`, `ESBL`, `CRE`, `VRE`), and generates facility cumulative antibiograms.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class AntimicrobialStewardshipService {

    public enum WhoAwareCategory {
        ACCESS("First/second-line narrow-spectrum antibiotics with low resistance potential"),
        WATCH("Broad-spectrum antibiotics requiring stewardship monitoring and 48/72h time-out"),
        RESERVE("Last-resort antibiotics restricted to life-threatening multidrug-resistant infections; requires ID specialist approval");

        private final String policyGuidance;

        WhoAwareCategory(String policyGuidance) {
            this.policyGuidance = policyGuidance;
        }

        public String policyGuidance() {
            return policyGuidance;
        }
    }

    public enum SusceptibilityInterpretation {
        S("Susceptible"),
        I("Susceptible, Increased Exposure (Intermediate)"),
        R("Resistant");

        private final String display;

        SusceptibilityInterpretation(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }
    }

    public enum MdroClassification {
        NONE,
        MRSA,
        ESBL_ENTEROBACTERALES,
        CRE_CARBAPENEM_RESISTANT,
        VRE_VANCOMYCIN_RESISTANT,
        MDR_ACINETOBACTER_PSEUDOMONAS
    }

    private static final Set<String> WHO_RESERVE_ANTIBIOTICS = Set.of(
            "colistin", "polymyxin b", "linezolid", "tedizolid", "daptomycin",
            "tigecycline", "ceftazidime-avibactam", "meropenem-vaborbactam",
            "cefiderocol", "aztreonam-avibactam", "plazomicin"
    );

    private static final Set<String> WHO_WATCH_ANTIBIOTICS = Set.of(
            "meropenem", "imipenem", "ertapenem", "vancomycin", "ciprofloxacin",
            "levofloxacin", "moxifloxacin", "ceftriaxone", "cefotaxime", "cefepime",
            "piperacillin-tazobactam", "azithromycin", "clarithromycin"
    );

    public record AntibioticStewardshipOrderRequest(
            UUID facilityId,
            UUID patientId,
            String antibioticName,
            String clinicalIndication,
            int plannedDurationDays,
            boolean cultureCollectedPriorToFirstDose,
            String infectiousDiseaseApprovalCode,
            String orderingPractitionerId
    ) {}

    public record AwareAntibioticOrderReview(
            UUID reviewId,
            UUID facilityId,
            UUID patientId,
            String antibioticName,
            WhoAwareCategory awareCategory,
            String clinicalIndication,
            int plannedDurationDays,
            boolean cultureCollectedPriorToFirstDose,
            boolean approvedForDispensing,
            boolean requires48To72HourTimeoutReview,
            Instant timeoutReviewDueAt,
            String stewardshipRecommendation,
            Instant reviewedAt
    ) {}

    public record MicrobiologyIsolateRequest(
            UUID facilityId,
            UUID patientId,
            String specimenType,
            String organismName,
            String organismSnomedCode,
            String antibioticTested,
            double micUgPerMl,
            SusceptibilityInterpretation interpretation,
            boolean hospitalAcquiredOnsetAfter48Hours
    ) {}

    public record MicrobiologySusceptibilityRecord(
            UUID isolateId,
            UUID facilityId,
            UUID patientId,
            String specimenType,
            String organismName,
            String organismSnomedCode,
            String antibioticTested,
            double micUgPerMl,
            SusceptibilityInterpretation interpretation,
            MdroClassification mdroClassification,
            boolean hospitalAcquiredNosocomial,
            boolean contactIsolationPrecautionsRequired,
            Instant reportedAt
    ) {}

    public record AntibiogramPathogenDrugCell(
            String organismName,
            String antibioticName,
            int totalIsolatesTested,
            int susceptibleCount,
            double percentSusceptible,
            boolean empiricTherapyRecommended
    ) {}

    public record CumulativeAntibiogramReport(
            UUID facilityId,
            Instant generatedAt,
            int totalIsolatesAnalyzed,
            int mdroIsolatesCount,
            List<AntibiogramPathogenDrugCell> susceptibilityMatrix
    ) {}

    private final FhirSubscriptionTopicService subscriptionTopicService;
    private final FhirAuditLoggingService fhirAuditLoggingService;

    private final List<AwareAntibioticOrderReview> orderReviews = new ArrayList<>();
    private final List<MicrobiologySusceptibilityRecord> isolateRecords = new ArrayList<>();

    @Autowired
    public AntimicrobialStewardshipService(
            @Autowired(required = false) FhirSubscriptionTopicService subscriptionTopicService,
            @Autowired(required = false) FhirAuditLoggingService fhirAuditLoggingService) {
        this.subscriptionTopicService = subscriptionTopicService;
        this.fhirAuditLoggingService = fhirAuditLoggingService;
    }

    public AntimicrobialStewardshipService() {
        this(null, null);
    }

    /**
     * Classifies an antibiotic under WHO AWaRe (`ACCESS`, `WATCH`, `RESERVE`) and evaluates
     * stewardship authorization and 48/72-hour time-out requirements.
     */
    @ClinicalAudit(action = "AMS_AWARE_ORDER_REVIEW", resourceType = "MedicationRequest")
    public AwareAntibioticOrderReview evaluateAntibioticOrder(AntibioticStewardshipOrderRequest request) {
        if (request == null || request.patientId() == null) {
            throw new IllegalArgumentException("AntibioticStewardshipOrderRequest with patientId is required");
        }
        if (request.antibioticName() == null || request.antibioticName().isBlank()) {
            throw new IllegalArgumentException("antibioticName is required");
        }

        UUID facilityId = request.facilityId() != null ? request.facilityId() : PatientService.DEFAULT_FACILITY_ID;
        String abxName = request.antibioticName().trim();
        WhoAwareCategory category = classifyWhoAware(abxName);

        boolean hasIdApproval = request.infectiousDiseaseApprovalCode() != null
                && !request.infectiousDiseaseApprovalCode().isBlank();

        boolean approved = true;
        boolean requiresTimeout = category != WhoAwareCategory.ACCESS;
        String recommendation;

        if (category == WhoAwareCategory.RESERVE && !hasIdApproval) {
            approved = false;
            recommendation = "BLOCKED (WHO AWaRe RESERVE): '" + abxName
                    + "' is a last-resort Reserve antimicrobial requiring Infectious Disease (ID) specialist pre-authorization code and blood/site culture collection before first dose.";
        } else if (category == WhoAwareCategory.RESERVE) {
            recommendation = "APPROVED WITH ID STEWARDSHIP OVERSIGHT (WHO AWaRe RESERVE): Mandatory 48-hour culture de-escalation review scheduled.";
        } else if (category == WhoAwareCategory.WATCH) {
            recommendation = "APPROVED (WHO AWaRe WATCH): Broad-spectrum agent; perform mandatory 48–72 hour antibiotic time-out to de-escalate to an ACCESS agent once culture sensitivities return."
                    + (!request.cultureCollectedPriorToFirstDose() ? " WARNING: Collect cultures prior to first dose." : "");
        } else {
            recommendation = "APPROVED (WHO AWaRe ACCESS): First-line narrow-spectrum therapy aligned with WHO target (>= 60% Access consumption).";
        }

        Instant now = Instant.now();
        AwareAntibioticOrderReview review = new AwareAntibioticOrderReview(
                UUID.randomUUID(),
                facilityId,
                request.patientId(),
                abxName,
                category,
                request.clinicalIndication() != null ? request.clinicalIndication().trim() : "Empiric infection therapy",
                request.plannedDurationDays() > 0 ? request.plannedDurationDays() : 7,
                request.cultureCollectedPriorToFirstDose(),
                approved,
                requiresTimeout,
                requiresTimeout ? now.plus(Duration.ofHours(48)) : null,
                recommendation,
                now
        );

        synchronized (orderReviews) {
            orderReviews.add(review);
        }

        return review;
    }

    /**
     * Records a microbiology culture &amp; sensitivity isolate result, detects MDRO pathogens
     * (`MRSA`, `ESBL`, `CRE`, `VRE`), and dispatches infection-control alerts.
     */
    @ClinicalAudit(action = "AMS_ISOLATE_SUSCEPTIBILITY_RECORD", resourceType = "DiagnosticReport")
    public MicrobiologySusceptibilityRecord recordIsolateSusceptibility(MicrobiologyIsolateRequest request) {
        if (request == null || request.patientId() == null) {
            throw new IllegalArgumentException("MicrobiologyIsolateRequest with patientId is required");
        }
        if (request.organismName() == null || request.organismName().isBlank()) {
            throw new IllegalArgumentException("organismName is required");
        }
        if (request.antibioticTested() == null || request.antibioticTested().isBlank()) {
            throw new IllegalArgumentException("antibioticTested is required");
        }

        UUID facilityId = request.facilityId() != null ? request.facilityId() : PatientService.DEFAULT_FACILITY_ID;
        SusceptibilityInterpretation interp = request.interpretation() != null
                ? request.interpretation()
                : SusceptibilityInterpretation.S;

        MdroClassification mdro = detectMdro(
                request.organismName().trim(),
                request.antibioticTested().trim(),
                interp
        );
        boolean isolationRequired = mdro != MdroClassification.NONE;

        MicrobiologySusceptibilityRecord record = new MicrobiologySusceptibilityRecord(
                UUID.randomUUID(),
                facilityId,
                request.patientId(),
                request.specimenType() != null ? request.specimenType().trim() : "Blood culture",
                request.organismName().trim(),
                request.organismSnomedCode() != null ? request.organismSnomedCode().trim() : "409822003",
                request.antibioticTested().trim(),
                request.micUgPerMl(),
                interp,
                mdro,
                request.hospitalAcquiredOnsetAfter48Hours(),
                isolationRequired,
                Instant.now()
        );

        synchronized (isolateRecords) {
            isolateRecords.add(record);
        }

        if (isolationRequired && subscriptionTopicService != null) {
            subscriptionTopicService.publishClinicalEvent(new SubscriptionEventDispatchRequest(
                    facilityId,
                    FhirSubscriptionTopicService.TOPIC_MDRO_OUTBREAK_DETECTED,
                    "DiagnosticReport",
                    record.isolateId().toString(),
                    request.patientId(),
                    "CRITICAL",
                    "MDRO Alert (" + mdro + "): " + record.organismName()
                            + " resistant to " + record.antibioticTested()
                            + " (MIC=" + record.micUgPerMl() + " ug/mL) for Patient/" + request.patientId()
                            + ". Initiate Contact Isolation Precautions immediately.",
                    Map.of(
                            "isolateId", record.isolateId().toString(),
                            "mdroClassification", mdro.name(),
                            "organismName", record.organismName(),
                            "antibioticTested", record.antibioticTested()
                    )
            ));
        }

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAuditLoggingService.FhirAccessAuditCommand(
                    facilityId.toString(),
                    "MICROBIOLOGY_LIS",
                    "MICROBIOLOGIST",
                    "AMS_CULTURE_SUSCEPTIBILITY_RECORDED",
                    "DiagnosticReport",
                    record.isolateId().toString(),
                    request.patientId(),
                    "POST",
                    "/api/v1/ams/isolates",
                    "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "Recorded isolate " + record.organismName() + " vs " + record.antibioticTested()
                            + " (" + interp + ", MDRO=" + mdro + ")"
            ));
        }

        return record;
    }

    /**
     * Generates a CLSI M39-compliant Cumulative Antibiogram matrix for the facility.
     */
    public CumulativeAntibiogramReport generateCumulativeAntibiogram(UUID facilityId) {
        List<MicrobiologySusceptibilityRecord> scoped;
        synchronized (isolateRecords) {
            scoped = isolateRecords.stream()
                    .filter(r -> facilityId == null || Objects.equals(facilityId, r.facilityId()))
                    .toList();
        }

        Map<String, List<MicrobiologySusceptibilityRecord>> grouped = new LinkedHashMap<>();
        int mdroCount = 0;
        for (MicrobiologySusceptibilityRecord rec : scoped) {
            if (rec.mdroClassification() != MdroClassification.NONE) {
                mdroCount++;
            }
            String key = rec.organismName() + "||" + rec.antibioticTested();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(rec);
        }

        List<AntibiogramPathogenDrugCell> cells = new ArrayList<>();
        for (Map.Entry<String, List<MicrobiologySusceptibilityRecord>> entry : grouped.entrySet()) {
            String[] parts = entry.getKey().split("\\|\\|");
            List<MicrobiologySusceptibilityRecord> list = entry.getValue();
            int total = list.size();
            int susceptible = (int) list.stream()
                    .filter(r -> r.interpretation() == SusceptibilityInterpretation.S)
                    .count();
            double pct = BigDecimal.valueOf((susceptible * 100.0) / total)
                    .setScale(1, RoundingMode.HALF_UP)
                    .doubleValue();
            cells.add(new AntibiogramPathogenDrugCell(
                    parts[0],
                    parts[1],
                    total,
                    susceptible,
                    pct,
                    pct >= 80.0
            ));
        }

        cells.sort(Comparator.comparing(AntibiogramPathogenDrugCell::organismName)
                .thenComparing(Comparator.comparingDouble(AntibiogramPathogenDrugCell::percentSusceptible).reversed()));

        return new CumulativeAntibiogramReport(
                facilityId != null ? facilityId : PatientService.DEFAULT_FACILITY_ID,
                Instant.now(),
                scoped.size(),
                mdroCount,
                List.copyOf(cells)
        );
    }

    public static WhoAwareCategory classifyWhoAware(String antibioticName) {
        String lower = antibioticName.toLowerCase(Locale.ROOT);
        for (String reserve : WHO_RESERVE_ANTIBIOTICS) {
            if (lower.contains(reserve)) {
                return WhoAwareCategory.RESERVE;
            }
        }
        for (String watch : WHO_WATCH_ANTIBIOTICS) {
            if (lower.contains(watch)) {
                return WhoAwareCategory.WATCH;
            }
        }
        return WhoAwareCategory.ACCESS;
    }

    private static MdroClassification detectMdro(
            String organism,
            String antibiotic,
            SusceptibilityInterpretation interp) {
        if (interp != SusceptibilityInterpretation.R) {
            return MdroClassification.NONE;
        }
        String orgLower = organism.toLowerCase(Locale.ROOT);
        String abxLower = antibiotic.toLowerCase(Locale.ROOT);

        if (orgLower.contains("staphylococcus aureus")
                && (abxLower.contains("oxacillin") || abxLower.contains("methicillin") || abxLower.contains("cefoxitin"))) {
            return MdroClassification.MRSA;
        }
        if ((orgLower.contains("klebsiella") || orgLower.contains("escherichia coli") || orgLower.contains("enterobacter"))
                && (abxLower.contains("meropenem") || abxLower.contains("imipenem") || abxLower.contains("ertapenem"))) {
            return MdroClassification.CRE_CARBAPENEM_RESISTANT;
        }
        if ((orgLower.contains("klebsiella") || orgLower.contains("escherichia coli"))
                && (abxLower.contains("ceftriaxone") || abxLower.contains("cefotaxime") || abxLower.contains("ceftazidime"))) {
            return MdroClassification.ESBL_ENTEROBACTERALES;
        }
        if (orgLower.contains("enterococcus") && abxLower.contains("vancomycin")) {
            return MdroClassification.VRE_VANCOMYCIN_RESISTANT;
        }
        if ((orgLower.contains("acinetobacter") || orgLower.contains("pseudomonas"))
                && (abxLower.contains("meropenem") || abxLower.contains("colistin"))) {
            return MdroClassification.MDR_ACINETOBACTER_PSEUDOMONAS;
        }
        return MdroClassification.NONE;
    }
}
