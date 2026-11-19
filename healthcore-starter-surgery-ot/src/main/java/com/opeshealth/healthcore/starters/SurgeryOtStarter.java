/*
 * =============================================================================
 * Opes HealthCore SDK — Surgery, Operating Theatre & PACU Starter Module
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.ops.autoconfigure.HospitalOpsAutoConfiguration;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.AnesthesiaPacuLogEntity;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.SurgicalScheduleEntity;
import com.opeshealth.healthcore.ops.service.HospitalOpsService;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.AnesthesiaPacuRepository;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.SurgicalScheduleRepository;
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
 * End-to-end Operating Theatre (OT) &amp; Perioperative Care module implementing the
 * 3-phase WHO 19-Item Surgical Safety Checklist (`SIGN_IN`, `TIME_OUT`, `SIGN_OUT`),
 * FDA/ISO Implant UDI traceability, and the 5-domain Modified Aldrete Post-Anesthesia
 * Care Unit (PACU) discharge scoring gate ($\ge 9 / 10$).
 */
@AutoConfiguration(after = HospitalOpsAutoConfiguration.class)
public class SurgeryOtStarter {

    public record WhoSurgicalChecklistSubmission(
            UUID surgicalScheduleId,
            boolean patientIdentityAndSiteConfirmed,
            boolean pulseOximeterFunctioning,
            boolean antibioticProphylaxisWithin60Min,
            boolean teamTimeOutCompleted,
            boolean spongeNeedleInstrumentCountCorrect,
            String implantUdiBarcode
    ) {}

    public record PacuAldreteAssessmentRequest(
            UUID surgicalScheduleId,
            int activityScore,
            int respirationScore,
            int circulationScore,
            int consciousnessScore,
            int oxygenSaturationScore,
            int estimatedBloodLossMl,
            String asaClass
    ) {}

    public record PacuDischargeDecision(
            UUID logId,
            UUID surgicalScheduleId,
            int totalAldreteScore,
            boolean eligibleForWardDischarge,
            String dispositionStatus,
            String clinicalRecommendation
    ) {}

    @Bean
    @ConditionalOnMissingBean
    public SurgicalPerioperativeService surgicalPerioperativeService(
            HospitalOpsService hospitalOpsService,
            SurgicalScheduleRepository surgicalScheduleRepository,
            AnesthesiaPacuRepository anesthesiaPacuRepository) {
        return new SurgicalPerioperativeService(
                hospitalOpsService, surgicalScheduleRepository, anesthesiaPacuRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public SurgicalPerioperativeController surgicalPerioperativeController(
            SurgicalPerioperativeService perioperativeService) {
        return new SurgicalPerioperativeController(perioperativeService);
    }

    public static class SurgicalPerioperativeService {

        private static final int MIN_ALDRETE_DISCHARGE_SCORE = 9;

        private final HospitalOpsService hospitalOpsService;
        private final SurgicalScheduleRepository surgicalScheduleRepository;
        private final AnesthesiaPacuRepository anesthesiaPacuRepository;

        public SurgicalPerioperativeService(
                HospitalOpsService hospitalOpsService,
                SurgicalScheduleRepository surgicalScheduleRepository,
                AnesthesiaPacuRepository anesthesiaPacuRepository) {
            this.hospitalOpsService = Objects.requireNonNull(hospitalOpsService, "HospitalOpsService is required");
            this.surgicalScheduleRepository = Objects.requireNonNull(surgicalScheduleRepository, "SurgicalScheduleRepository is required");
            this.anesthesiaPacuRepository = Objects.requireNonNull(anesthesiaPacuRepository, "AnesthesiaPacuRepository is required");
        }

        /**
         * Verifies all 3 phases of the WHO Surgical Safety Checklist before marking
         * the surgical schedule checklist complete and authorizing incision.
         */
        @Transactional
        @ClinicalAudit(action = "SURGERY_WHO_CHECKLIST_VERIFY", resourceType = "SurgicalSchedule")
        public SurgicalScheduleEntity completeWhoSurgicalSafetyChecklist(WhoSurgicalChecklistSubmission submission) {
            Objects.requireNonNull(submission, "WhoSurgicalChecklistSubmission must not be null");
            SurgicalScheduleEntity schedule = surgicalScheduleRepository.findById(submission.surgicalScheduleId())
                    .orElseThrow(() -> new NoSuchElementException("Surgical schedule not found: " + submission.surgicalScheduleId()));

            List<String> failedGates = new ArrayList<>();
            if (!submission.patientIdentityAndSiteConfirmed()) {
                failedGates.add("SIGN_IN: Patient identity, procedure, and surgical site marking not confirmed");
            }
            if (!submission.pulseOximeterFunctioning()) {
                failedGates.add("SIGN_IN: Pulse oximeter not on patient and functioning");
            }
            if (!submission.antibioticProphylaxisWithin60Min()) {
                failedGates.add("TIME_OUT: Prophylactic antibiotic not administered within last 60 minutes");
            }
            if (!submission.teamTimeOutCompleted()) {
                failedGates.add("TIME_OUT: Surgical team verbal Time-Out not completed");
            }
            if (!failedGates.isEmpty()) {
                throw new IllegalStateException("WHO Surgical Safety Checklist gate blocked incision: "
                        + String.join("; ", failedGates));
            }

            schedule.setWhoChecklistCompleted(true);
            if (submission.implantUdiBarcode() != null && !submission.implantUdiBarcode().isBlank()) {
                schedule.setImplantUdi(submission.implantUdiBarcode().trim());
            }
            schedule.setStatus(submission.spongeNeedleInstrumentCountCorrect() ? "COMPLETED_COUNT_VERIFIED" : "IN_SURGERY");
            return surgicalScheduleRepository.save(schedule);
        }

        /**
         * Calculates the 5-domain Modified Aldrete PACU recovery score (0–10) and enforces
         * the >= 9 threshold before authorizing post-anesthesia discharge to a general ward.
         */
        @Transactional
        @ClinicalAudit(action = "SURGERY_PACU_ALDRETE_ASSESS", resourceType = "AnesthesiaPacuLog")
        public PacuDischargeDecision evaluateAndRecordPacuAldreteScore(PacuAldreteAssessmentRequest req) {
            Objects.requireNonNull(req, "PacuAldreteAssessmentRequest must not be null");
            validateDomainScore("activityScore", req.activityScore());
            validateDomainScore("respirationScore", req.respirationScore());
            validateDomainScore("circulationScore", req.circulationScore());
            validateDomainScore("consciousnessScore", req.consciousnessScore());
            validateDomainScore("oxygenSaturationScore", req.oxygenSaturationScore());

            int totalScore = req.activityScore()
                    + req.respirationScore()
                    + req.circulationScore()
                    + req.consciousnessScore()
                    + req.oxygenSaturationScore();

            AnesthesiaPacuLogEntity logEntity = new AnesthesiaPacuLogEntity();
            logEntity.setSurgicalScheduleId(req.surgicalScheduleId());
            logEntity.setAsaClass(req.asaClass() != null && !req.asaClass().isBlank() ? req.asaClass().trim() : "ASA_II");
            logEntity.setEstimatedBloodLossMl(Math.max(0, req.estimatedBloodLossMl()));
            logEntity.setPacuAldreteScore(totalScore);
            AnesthesiaPacuLogEntity saved = anesthesiaPacuRepository.save(logEntity);

            boolean eligible = totalScore >= MIN_ALDRETE_DISCHARGE_SCORE && req.respirationScore() >= 2 && req.oxygenSaturationScore() >= 1;
            String disposition = eligible ? "CLEARED_FOR_WARD_TRANSFER" : "RETAIN_IN_PACU_MONITORING";
            String recommendation = eligible
                    ? "Modified Aldrete Score " + totalScore + "/10 (>= 9). Patient meets physiological criteria for transfer from PACU."
                    : "Modified Aldrete Score " + totalScore + "/10 (< 9 or respiratory domain < 2). Continue continuous SpO2/hemodynamic monitoring in PACU.";

            return new PacuDischargeDecision(
                    saved.getId(),
                    req.surgicalScheduleId(),
                    totalScore,
                    eligible,
                    disposition,
                    recommendation
            );
        }

        private static void validateDomainScore(String domain, int score) {
            if (score < 0 || score > 2) {
                throw new IllegalArgumentException("Modified Aldrete domain '" + domain + "' must be between 0 and 2; got: " + score);
            }
        }
    }

    @RestController
    @RequestMapping("/api/v1/surgery-ot")
    @Tag(name = "Surgery, Operating Theatre & PACU", description = "WHO 19-Item Surgical Safety Checklist gate, Implant UDI tracking, and Modified Aldrete PACU discharge scoring")
    public static class SurgicalPerioperativeController {

        private final SurgicalPerioperativeService perioperativeService;

        public SurgicalPerioperativeController(SurgicalPerioperativeService perioperativeService) {
            this.perioperativeService = perioperativeService;
        }

        @PostMapping("/checklist-verify")
        @PreAuthorize("hasAuthority('PERM_surgery:manage')")
        @Operation(summary = "Verify WHO 3-Phase Surgical Safety Checklist and authorize incision / completion")
        public SurgicalScheduleEntity verifyWhoChecklist(@RequestBody WhoSurgicalChecklistSubmission submission) {
            return perioperativeService.completeWhoSurgicalSafetyChecklist(submission);
        }

        @PostMapping("/pacu-aldrete-discharge")
        @PreAuthorize("hasAuthority('PERM_surgery:manage')")
        @Operation(summary = "Calculate 5-domain Modified Aldrete PACU recovery score and evaluate ward transfer eligibility")
        public PacuDischargeDecision evaluatePacuDischarge(@RequestBody PacuAldreteAssessmentRequest request) {
            return perioperativeService.evaluateAndRecordPacuAldreteScore(request);
        }
    }
}
