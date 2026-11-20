/*
 * =============================================================================
 * Opes HealthCore SDK — WHO EPI Immunization & HIV/TB Care Cascades Starter
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.publichealth.autoconfigure.PublicHealthAutoConfiguration;
import com.opeshealth.healthcore.publichealth.domain.PublicHealthEntities.ChronicCascadeEntity;
import com.opeshealth.healthcore.publichealth.domain.PublicHealthEntities.ImmunizationRecordEntity;
import com.opeshealth.healthcore.publichealth.service.PublicHealthService;
import com.opeshealth.healthcore.publichealth.service.PublicHealthService.ChronicCascadeRepository;
import com.opeshealth.healthcore.publichealth.service.PublicHealthService.ImmunizationRepository;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * End-to-end WHO Expanded Programme on Immunization (EPI) Catch-Up Scheduler and
 * UNAIDS 95-95-95 HIV ART Viral Load / TB DOT Care Cascade module persisted in PostgreSQL.
 */
@AutoConfiguration(after = PublicHealthAutoConfiguration.class)
public class ProgramsImmunizationStarter {

    public record EpiPatientScheduleStatus(
            UUID patientId,
            int totalDosesRecorded,
            List<String> completedVaccineDoses,
            List<String> overdueCatchUpVaccines,
            boolean defaulterFollowUpRequired
    ) {}

    public record UnaidsCascadeMetrics(
            String programName,
            int enrolledPatients,
            int activeOnArtOrTreatment,
            int viralLoadSuppressedBelow1000,
            int undetectableBelow50,
            int requiringEnhancedAdherenceCounseling,
            double viralSuppressionRatePercent
    ) {}

    @Bean
    @ConditionalOnMissingBean
    public WhoEpiAndChronicCascadeService whoEpiAndChronicCascadeService(
            PublicHealthService publicHealthService,
            ImmunizationRepository immunizationRepository,
            ChronicCascadeRepository chronicCascadeRepository) {
        return new WhoEpiAndChronicCascadeService(
                publicHealthService, immunizationRepository, chronicCascadeRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public WhoEpiAndChronicCascadeController whoEpiAndChronicCascadeController(
            WhoEpiAndChronicCascadeService epiCascadeService) {
        return new WhoEpiAndChronicCascadeController(epiCascadeService);
    }

    public static class WhoEpiAndChronicCascadeService {

        private final PublicHealthService publicHealthService;
        private final ImmunizationRepository immunizationRepository;
        private final ChronicCascadeRepository chronicCascadeRepository;

        public WhoEpiAndChronicCascadeService(
                PublicHealthService publicHealthService,
                ImmunizationRepository immunizationRepository,
                ChronicCascadeRepository chronicCascadeRepository) {
            this.publicHealthService = Objects.requireNonNull(publicHealthService, "PublicHealthService is required");
            this.immunizationRepository = Objects.requireNonNull(immunizationRepository, "ImmunizationRepository is required");
            this.chronicCascadeRepository = Objects.requireNonNull(chronicCascadeRepository, "ChronicCascadeRepository is required");
        }

        /**
         * Evaluates a child's recorded immunizations in PostgreSQL against the WHO EPI
         * schedule and identifies any overdue doses (> 14 days past `nextDueDate`).
         */
        @Transactional(readOnly = true)
        public EpiPatientScheduleStatus evaluatePatientEpiSchedule(UUID patientId) {
            if (patientId == null) {
                throw new IllegalArgumentException("patientId is required for WHO EPI schedule evaluation");
            }
            List<ImmunizationRecordEntity> records = immunizationRepository.findAll().stream()
                    .filter(r -> patientId.equals(r.getPatientId()))
                    .toList();

            LocalDate today = LocalDate.now();
            List<String> completed = new ArrayList<>();
            List<String> overdue = new ArrayList<>();

            for (ImmunizationRecordEntity r : records) {
                String label = r.getCvxCode() + " (Dose " + r.getDoseNumber() + ")";
                completed.add(label);
                if (r.getNextDueDate() != null && ChronoUnit.DAYS.between(r.getNextDueDate(), today) > 14) {
                    overdue.add(r.getCvxCode() + " Dose " + (r.getDoseNumber() + 1)
                            + " (due " + r.getNextDueDate() + ")");
                }
            }

            return new EpiPatientScheduleStatus(
                    patientId,
                    records.size(),
                    List.copyOf(completed),
                    List.copyOf(overdue),
                    !overdue.isEmpty()
            );
        }

        /**
         * Computes UNAIDS 95-95-95 HIV ART viral load suppression (< 1,000 copies/mL)
         * and Enhanced Adherence Counseling (EAC) triggers from PostgreSQL.
         */
        @Transactional(readOnly = true)
        @ClinicalAudit(action = "PROGRAM_UNAIDS_CASCADE_METRICS", resourceType = "ChronicCascade")
        public UnaidsCascadeMetrics calculateUnaidsCascadeMetrics(String programName) {
            String targetProgram = programName != null && !programName.isBlank()
                    ? programName.trim().toUpperCase(Locale.ROOT)
                    : "HIV_ART";

            List<ChronicCascadeEntity> rows = chronicCascadeRepository.findAll().stream()
                    .filter(c -> targetProgram.equalsIgnoreCase(c.getProgramCode()))
                    .toList();

            int enrolled = rows.size();
            int active = 0;
            int suppressed = 0;
            int undetectable = 0;
            int requiringEac = 0;

            for (ChronicCascadeEntity row : rows) {
                if (row.getRegimen() != null && !row.getRegimen().isBlank()) {
                    active++;
                }
                Integer vl = row.getViralLoadCopiesMl();
                if (vl != null) {
                    if (vl < 1000L) {
                        suppressed++;
                        if (vl < 50L) {
                            undetectable++;
                        }
                    } else {
                        requiringEac++;
                    }
                }
            }

            double suppressionPct = active == 0 ? 100.0 : Math.round((suppressed * 10000.0) / active) / 100.0;
            return new UnaidsCascadeMetrics(
                    targetProgram,
                    enrolled,
                    active,
                    suppressed,
                    undetectable,
                    requiringEac,
                    suppressionPct
            );
        }
    }

    @RestController
    @RequestMapping("/api/v1/programs-immunization")
    @Tag(name = "WHO EPI Immunization & HIV/TB Care Cascades", description = "WHO EPI defaulter catch-up tracking and UNAIDS 95-95-95 HIV ART viral load suppression analytics")
    public static class WhoEpiAndChronicCascadeController {

        private final WhoEpiAndChronicCascadeService epiCascadeService;

        public WhoEpiAndChronicCascadeController(WhoEpiAndChronicCascadeService epiCascadeService) {
            this.epiCascadeService = epiCascadeService;
        }

        @GetMapping("/epi-schedule/{patientId}")
        @PreAuthorize("hasAuthority('PERM_immunization:write') or hasAuthority('PERM_patient:read')")
        @Operation(summary = "Evaluate a patient's WHO EPI immunization schedule and catch-up alerts")
        public EpiPatientScheduleStatus evaluateEpiSchedule(@PathVariable UUID patientId) {
            return epiCascadeService.evaluatePatientEpiSchedule(patientId);
        }

        @GetMapping("/unaids-95-95-95-metrics")
        @PreAuthorize("hasAuthority('PERM_program:manage') or hasAuthority('PERM_surveillance:report')")
        @Operation(summary = "Compute UNAIDS 95-95-95 viral suppression and EAC adherence metrics")
        public UnaidsCascadeMetrics getUnaidsMetrics(@RequestParam(defaultValue = "HIV_ART") String programName) {
            return epiCascadeService.calculateUnaidsCascadeMetrics(programName);
        }
    }
}
