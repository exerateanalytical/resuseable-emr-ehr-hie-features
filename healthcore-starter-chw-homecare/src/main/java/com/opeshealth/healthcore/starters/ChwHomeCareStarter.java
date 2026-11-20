/*
 * =============================================================================
 * Opes HealthCore SDK — CHW GPS Households, iCCM/MUAC & Home Care Starter
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.publichealth.autoconfigure.PublicHealthAutoConfiguration;
import com.opeshealth.healthcore.publichealth.domain.PublicHealthEntities.ChwHouseholdEntity;
import com.opeshealth.healthcore.publichealth.domain.PublicHealthEntities.ChwVisitEntity;
import com.opeshealth.healthcore.publichealth.domain.PublicHealthEntities.HomeCarePlanEntity;
import com.opeshealth.healthcore.publichealth.service.PublicHealthService;
import com.opeshealth.healthcore.publichealth.service.PublicHealthService.ChwHouseholdRepository;
import com.opeshealth.healthcore.publichealth.service.PublicHealthService.ChwVisitRepository;
import com.opeshealth.healthcore.publichealth.service.PublicHealthService.HomeCarePlanRepository;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * End-to-end Community Health Worker (CHW), WHO/UNICEF iCCM (Integrated Community
 * Case Management), MUAC Malnutrition Screening, and Home Care Remote Patient Monitoring
 * module with PostgreSQL persistence.
 */
@AutoConfiguration(after = PublicHealthAutoConfiguration.class)
public class ChwHomeCareStarter {

    public record IccmPediatricTriageInput(
            UUID householdId,
            UUID patientId,
            int ageInMonths,
            int respiratoryRatePerMin,
            Integer muacMm,
            boolean malariaRdtPositive,
            boolean diarrheaPresent,
            boolean generalDangerSignsPresent
    ) {}

    public record IccmTriageOutcome(
            UUID visitId,
            String nutritionalStatus,
            boolean fastBreathingPneumoniaSuspected,
            boolean urgentHospitalReferralRequired,
            String referralQrToken,
            List<String> recommendedCommunityTreatments
    ) {}

    @Bean
    @ConditionalOnMissingBean
    public ChwIccmAndHomeCareTriageService chwIccmAndHomeCareTriageService(
            PublicHealthService publicHealthService,
            ChwHouseholdRepository householdRepository,
            ChwVisitRepository visitRepository,
            HomeCarePlanRepository homeCarePlanRepository) {
        return new ChwIccmAndHomeCareTriageService(
                publicHealthService, householdRepository, visitRepository, homeCarePlanRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChwIccmAndHomeCareController chwIccmAndHomeCareController(
            ChwIccmAndHomeCareTriageService triageService) {
        return new ChwIccmAndHomeCareController(triageService);
    }

    public static class ChwIccmAndHomeCareTriageService {

        private final PublicHealthService publicHealthService;
        private final ChwHouseholdRepository householdRepository;
        private final ChwVisitRepository visitRepository;
        private final HomeCarePlanRepository homeCarePlanRepository;

        public ChwIccmAndHomeCareTriageService(
                PublicHealthService publicHealthService,
                ChwHouseholdRepository householdRepository,
                ChwVisitRepository visitRepository,
                HomeCarePlanRepository homeCarePlanRepository) {
            this.publicHealthService = Objects.requireNonNull(publicHealthService, "PublicHealthService is required");
            this.householdRepository = Objects.requireNonNull(householdRepository, "ChwHouseholdRepository is required");
            this.visitRepository = Objects.requireNonNull(visitRepository, "ChwVisitRepository is required");
            this.homeCarePlanRepository = Objects.requireNonNull(homeCarePlanRepository, "HomeCarePlanRepository is required");
        }

        /**
         * Evaluates an under-5 pediatric field visit using WHO/UNICEF iCCM guidelines
         * (age-specific tachypnea cutoffs, MUAC tape malnutrition thresholds, malaria RDT,
         * and IMCI danger signs), persists the CHW visit to PostgreSQL, and generates
         * actionable community treatment and referral QR tokens.
         */
        @Transactional
        @ClinicalAudit(action = "CHW_ICCM_PEDIATRIC_TRIAGE", resourceType = "ChwVisit")
        public IccmTriageOutcome evaluateIccmPediatricVisit(IccmPediatricTriageInput input) {
            Objects.requireNonNull(input, "IccmPediatricTriageInput must not be null");
            if (input.householdId() == null) {
                throw new IllegalArgumentException("householdId is required for CHW iCCM visit");
            }

            // WHO IMCI / iCCM age-specific tachypnea threshold
            int fastBreathingThreshold = input.ageInMonths() < 2 ? 60
                    : (input.ageInMonths() < 12 ? 50 : 40);
            boolean fastBreathing = input.respiratoryRatePerMin() >= fastBreathingThreshold;

            String nutritionStatus = "NORMAL_GREEN_MUAC";
            boolean samDanger = false;
            if (input.muacMm() != null) {
                if (input.muacMm() < 115) {
                    nutritionStatus = "SEVERE_ACUTE_MALNUTRITION_RED_MUAC";
                    samDanger = true;
                } else if (input.muacMm() < 125) {
                    nutritionStatus = "MODERATE_ACUTE_MALNUTRITION_YELLOW_MUAC";
                }
            }

            boolean urgentReferral = input.generalDangerSignsPresent() || samDanger || input.ageInMonths() < 2 && fastBreathing;
            List<String> treatments = new ArrayList<>();
            if (input.malariaRdtPositive()) {
                treatments.add(urgentReferral
                        ? "Pre-referral rectal Artesunate suppository + urgent hospital transfer"
                        : "ACT (Artemether-Lumefantrine) 3-day weight/age-banded oral course");
            }
            if (fastBreathing) {
                treatments.add("Oral dispersible Amoxicillin (40 mg/kg/dose BID for 5 days)");
            }
            if (input.diarrheaPresent()) {
                treatments.add("Low-osmolarity Oral Rehydration Salts (ORS) + Zinc sulfate (20 mg/day for 10-14 days)");
            }
            if (samDanger) {
                treatments.add("Ready-to-Use Therapeutic Food (RUTF) appetite test & stabilization referral");
            }

            ChwVisitEntity visit = new ChwVisitEntity();
            visit.setHouseholdId(input.householdId());
            visit.setPatientId(input.patientId());
            visit.setProgramType("ICCM_UNDER5");
            visit.setMuacMm(input.muacMm());
            visit.setDangerSignsPresent(urgentReferral);
            ChwVisitEntity saved = publicHealthService.recordChwVisit(visit);

            return new IccmTriageOutcome(
                    saved.getId(),
                    nutritionStatus,
                    fastBreathing,
                    urgentReferral,
                    saved.getReferralQrToken(),
                    List.copyOf(treatments)
            );
        }
    }

    @RestController
    @RequestMapping("/api/v1/chw-homecare")
    @Tag(name = "CHW Field Visits, iCCM/MUAC & Home Care", description = "WHO/UNICEF iCCM pediatric triage, MUAC malnutrition screening, and referral QR token generation")
    public static class ChwIccmAndHomeCareController {

        private final ChwIccmAndHomeCareTriageService triageService;

        public ChwIccmAndHomeCareController(ChwIccmAndHomeCareTriageService triageService) {
            this.triageService = triageService;
        }

        @PostMapping("/iccm-triage")
        @PreAuthorize("hasAuthority('PERM_chw:visit:write')")
        @Operation(summary = "Evaluate WHO/UNICEF iCCM under-5 field visit and persist CHW referral")
        public IccmTriageOutcome evaluateIccmVisit(@RequestBody IccmPediatricTriageInput input) {
            return triageService.evaluateIccmPediatricVisit(input);
        }
    }
}
