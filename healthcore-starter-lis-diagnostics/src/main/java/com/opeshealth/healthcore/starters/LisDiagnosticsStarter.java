/*
 * =============================================================================
 * Opes HealthCore SDK — Standalone LIS Westgard Multi-Rule & Panic QC Starter
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.pharmacylis.autoconfigure.PharmacyLisAutoConfiguration;
import com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities.DiagnosticReportEntity;
import com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities.LabSpecimenEntity;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService.DiagnosticReportRepository;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService.LabSpecimenRepository;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * End-to-end Laboratory Information System (LIS) Diagnostics &amp; Quality Control
 * module implementing Multi-Rule Westgard Levey-Jennings evaluation ($1_{2s}$ warning,
 * $1_{3s}$ random error rejection, $2_{2s}$ consecutive systematic error rejection,
 * $R_{4s}$ within-run range rejection) and CLIA critical panic-value read-back
 * acknowledgment tracking in PostgreSQL.
 */
@AutoConfiguration(after = PharmacyLisAutoConfiguration.class)
public class LisDiagnosticsStarter {

    public record WestgardControlRunRequest(
            String analyzerId,
            String loincCode,
            BigDecimal targetMean,
            BigDecimal targetStandardDeviation,
            List<BigDecimal> controlMeasurements
    ) {}

    public record WestgardMultiRuleEvaluation(
            String analyzerId,
            String loincCode,
            List<BigDecimal> zScores,
            boolean rule12sWarningTriggered,
            boolean rule13sRejected,
            boolean rule22sRejected,
            boolean ruleR4sRejected,
            String runStatus,
            String correctiveActionRequired
    ) {}

    public record CriticalPanicReadBackRequest(
            UUID diagnosticReportId,
            String notifiedClinicianId,
            String readBackConfirmedByLabScientistId
    ) {}

    @Bean
    @ConditionalOnMissingBean
    public LisWestgardAndCriticalResultService lisWestgardAndCriticalResultService(
            PharmacyLisService pharmacyLisService,
            LabSpecimenRepository labSpecimenRepository,
            DiagnosticReportRepository diagnosticReportRepository) {
        return new LisWestgardAndCriticalResultService(
                pharmacyLisService, labSpecimenRepository, diagnosticReportRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public LisWestgardAndCriticalResultController lisWestgardAndCriticalResultController(
            LisWestgardAndCriticalResultService lisDiagnosticsService) {
        return new LisWestgardAndCriticalResultController(lisDiagnosticsService);
    }

    public static class LisWestgardAndCriticalResultService {

        private final PharmacyLisService pharmacyLisService;
        private final LabSpecimenRepository labSpecimenRepository;
        private final DiagnosticReportRepository diagnosticReportRepository;

        public LisWestgardAndCriticalResultService(
                PharmacyLisService pharmacyLisService,
                LabSpecimenRepository labSpecimenRepository,
                DiagnosticReportRepository diagnosticReportRepository) {
            this.pharmacyLisService = Objects.requireNonNull(pharmacyLisService, "PharmacyLisService is required");
            this.labSpecimenRepository = Objects.requireNonNull(labSpecimenRepository, "LabSpecimenRepository is required");
            this.diagnosticReportRepository = Objects.requireNonNull(diagnosticReportRepository, "DiagnosticReportRepository is required");
        }

        /**
         * Evaluates sequential laboratory analyzer QC measurements against Westgard
         * multi-rules ($1_{2s}$, $1_{3s}$, $2_{2s}$, and $R_{4s}$) to detect both
         * random analytical imprecision and systematic calibration drift.
         */
        @ClinicalAudit(action = "LIS_WESTGARD_MULTI_RULE_QC", resourceType = "DiagnosticReport")
        public WestgardMultiRuleEvaluation evaluateWestgardControlRun(WestgardControlRunRequest req) {
            Objects.requireNonNull(req, "WestgardControlRunRequest must not be null");
            if (req.targetMean() == null || req.targetStandardDeviation() == null
                    || req.targetStandardDeviation().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("targetMean and positive targetStandardDeviation are required for Westgard QC");
            }
            List<BigDecimal> values = req.controlMeasurements() != null ? req.controlMeasurements() : List.of();
            if (values.isEmpty()) {
                throw new IllegalArgumentException("At least one control measurement is required");
            }

            List<BigDecimal> zScores = new ArrayList<>(values.size());
            boolean warn12s = false;
            boolean reject13s = false;
            boolean reject22s = false;
            boolean rejectR4s = false;

            BigDecimal prevZ = null;
            BigDecimal minZ = null;
            BigDecimal maxZ = null;

            for (BigDecimal val : values) {
                BigDecimal z = val.subtract(req.targetMean())
                        .divide(req.targetStandardDeviation(), 2, RoundingMode.HALF_UP);
                zScores.add(z);

                BigDecimal absZ = z.abs();
                if (absZ.compareTo(new BigDecimal("2.0")) > 0) {
                    warn12s = true;
                }
                if (absZ.compareTo(new BigDecimal("3.0")) > 0) {
                    reject13s = true;
                }
                if (prevZ != null) {
                    boolean bothAbovePos2 = z.compareTo(new BigDecimal("2.0")) > 0 && prevZ.compareTo(new BigDecimal("2.0")) > 0;
                    boolean bothBelowNeg2 = z.compareTo(new BigDecimal("-2.0")) < 0 && prevZ.compareTo(new BigDecimal("-2.0")) < 0;
                    if (bothAbovePos2 || bothBelowNeg2) {
                        reject22s = true;
                    }
                }
                if (minZ == null || z.compareTo(minZ) < 0) minZ = z;
                if (maxZ == null || z.compareTo(maxZ) > 0) maxZ = z;
                prevZ = z;
            }

            if (minZ != null && maxZ != null
                    && minZ.compareTo(BigDecimal.ZERO) < 0 && maxZ.compareTo(BigDecimal.ZERO) > 0
                    && maxZ.subtract(minZ).compareTo(new BigDecimal("4.0")) >= 0) {
                rejectR4s = true;
            }

            boolean rejected = reject13s || reject22s || rejectR4s;
            String status = rejected ? "OUT_OF_CONTROL_REJECTED" : (warn12s ? "WARNING_1_2S_IN_CONTROL" : "IN_CONTROL_ACCEPTED");
            String action;
            if (reject13s || rejectR4s) {
                action = "RANDOM_ERROR_DETECTED (1_3s / R_4s): Hold patient results, inspect reagent pipetting/bubbles, and repeat control run.";
            } else if (reject22s) {
                action = "SYSTEMATIC_DRIFT_DETECTED (2_2s): Hold patient results and recalibrate analyzer " + req.analyzerId() + " for LOINC " + req.loincCode() + ".";
            } else if (warn12s) {
                action = "1_2s Warning rule triggered: Run accepted, monitor next QC lot closely.";
            } else {
                action = "All Westgard multi-rules within +/-2SD statistical control limits. Authorized to release patient results.";
            }

            return new WestgardMultiRuleEvaluation(
                    req.analyzerId(),
                    req.loincCode(),
                    List.copyOf(zScores),
                    warn12s,
                    reject13s,
                    reject22s,
                    rejectR4s,
                    status,
                    action
            );
        }

        /**
         * Records a CLIA/ISO-15189 closed-loop verbal read-back acknowledgment for a
         * critical panic laboratory result in PostgreSQL (`hc_diagnostic_reports`).
         */
        @Transactional
        @ClinicalAudit(action = "LIS_CRITICAL_PANIC_READBACK", resourceType = "DiagnosticReport")
        public DiagnosticReportEntity recordCriticalPanicReadBack(CriticalPanicReadBackRequest req) {
            Objects.requireNonNull(req, "CriticalPanicReadBackRequest must not be null");
            if (req.notifiedClinicianId() == null || req.notifiedClinicianId().isBlank()
                    || req.readBackConfirmedByLabScientistId() == null || req.readBackConfirmedByLabScientistId().isBlank()) {
                throw new IllegalArgumentException("notifiedClinicianId and readBackConfirmedByLabScientistId are required for CLIA panic read-back");
            }
            DiagnosticReportEntity report = diagnosticReportRepository.findById(req.diagnosticReportId())
                    .orElseThrow(() -> new NoSuchElementException("DiagnosticReport not found: " + req.diagnosticReportId()));

            report.setPanicAcknowledgedBy(req.notifiedClinicianId().trim());
            return diagnosticReportRepository.save(report);
        }
    }

    @RestController
    @RequestMapping("/api/v1/lis-diagnostics")
    @Tag(name = "LIS Westgard Multi-Rule QC & Critical Panic Read-Back", description = "Westgard 1_2s/1_3s/2_2s/R_4s Levey-Jennings quality control and CLIA panic result read-back")
    public static class LisWestgardAndCriticalResultController {

        private final LisWestgardAndCriticalResultService lisDiagnosticsService;

        public LisWestgardAndCriticalResultController(LisWestgardAndCriticalResultService lisDiagnosticsService) {
            this.lisDiagnosticsService = lisDiagnosticsService;
        }

        @PostMapping("/westgard-multi-rule-evaluate")
        @PreAuthorize("hasAuthority('PERM_lab:verify') or hasAuthority('PERM_lab:result:enter')")
        @Operation(summary = "Evaluate analyzer QC run against Westgard 1_2s, 1_3s, 2_2s, and R_4s rules")
        public WestgardMultiRuleEvaluation evaluateWestgardQc(@RequestBody WestgardControlRunRequest request) {
            return lisDiagnosticsService.evaluateWestgardControlRun(request);
        }

        @PostMapping("/critical-result-acknowledge")
        @PreAuthorize("hasAuthority('PERM_lab:verify') or hasAuthority('PERM_order:sign')")
        @Operation(summary = "Record closed-loop clinician verbal read-back acknowledgment for a critical panic lab value")
        public DiagnosticReportEntity acknowledgePanicResult(@RequestBody CriticalPanicReadBackRequest request) {
            return lisDiagnosticsService.recordCriticalPanicReadBack(request);
        }
    }
}
