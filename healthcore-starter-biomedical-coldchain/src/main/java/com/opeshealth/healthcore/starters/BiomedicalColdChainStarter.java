/*
 * =============================================================================
 * Opes HealthCore SDK — Biomedical Equipment & 2°C-8°C Cold-Chain Starter
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.ops.autoconfigure.HospitalOpsAutoConfiguration;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.BiomedMaintenanceEntity;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.ColdChainTelemetryEntity;
import com.opeshealth.healthcore.ops.service.HospitalOpsService;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.BiomedMaintenanceRepository;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.ColdChainRepository;
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
import java.time.LocalDate;
import java.util.*;

/**
 * End-to-end Biomedical Engineering &amp; 2°C–8°C Cold-Chain Telemetry Governance module
 * implementing WHO Vaccine Vial Monitor (VVM) thermal stability analysis, freeze-damage
 * detection ($&lt; 0.5^\circ\text{C}$), and ISO 13485 biomedical preventive maintenance
 * (PM) &amp; calibration compliance tracking in PostgreSQL.
 */
@AutoConfiguration(after = HospitalOpsAutoConfiguration.class)
public class BiomedicalColdChainStarter {

    public record ColdChainStabilityReport(
            String sensorId,
            int totalReadingsAnalyzed,
            BigDecimal minTempCelsius,
            BigDecimal maxTempCelsius,
            BigDecimal meanTempCelsius,
            int heatExcursionReadingsAbove8C,
            int freezeExcursionReadingsBelow05C,
            String vvmStageEstimate,
            boolean vaccineLotQuarantineRequired,
            String clinicalDirective
    ) {}

    public record BiomedicalComplianceSummary(
            UUID facilityId,
            int totalTrackedAssets,
            int calibrationCompliantAssets,
            int overdueCalibrationAssets,
            double compliancePercentage,
            List<String> overdueAssetTags
    ) {}

    @Bean
    @ConditionalOnMissingBean
    public BiomedicalColdChainGovernanceService biomedicalColdChainGovernanceService(
            HospitalOpsService hospitalOpsService,
            ColdChainRepository coldChainRepository,
            BiomedMaintenanceRepository biomedMaintenanceRepository) {
        return new BiomedicalColdChainGovernanceService(
                hospitalOpsService, coldChainRepository, biomedMaintenanceRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public BiomedicalColdChainGovernanceController biomedicalColdChainGovernanceController(
            BiomedicalColdChainGovernanceService governanceService) {
        return new BiomedicalColdChainGovernanceController(governanceService);
    }

    public static class BiomedicalColdChainGovernanceService {

        private static final BigDecimal MAX_SAFE_TEMP = new BigDecimal("8.0");
        private static final BigDecimal MIN_SAFE_TEMP = new BigDecimal("2.0");
        private static final BigDecimal FREEZE_DAMAGE_TEMP = new BigDecimal("0.5");

        private final HospitalOpsService hospitalOpsService;
        private final ColdChainRepository coldChainRepository;
        private final BiomedMaintenanceRepository biomedMaintenanceRepository;

        public BiomedicalColdChainGovernanceService(
                HospitalOpsService hospitalOpsService,
                ColdChainRepository coldChainRepository,
                BiomedMaintenanceRepository biomedMaintenanceRepository) {
            this.hospitalOpsService = Objects.requireNonNull(hospitalOpsService, "HospitalOpsService is required");
            this.coldChainRepository = Objects.requireNonNull(coldChainRepository, "ColdChainRepository is required");
            this.biomedMaintenanceRepository = Objects.requireNonNull(biomedMaintenanceRepository, "BiomedMaintenanceRepository is required");
        }

        /**
         * Analyzes historical 2°C–8°C telemetry readings for a refrigerator/freezer sensor,
         * classifies WHO Vaccine Vial Monitor (VVM Stage I–IV) status, and determines whether
         * stored vaccines or reagents must be quarantined.
         */
        @Transactional(readOnly = true)
        public ColdChainStabilityReport analyzeSensorThermalStability(String sensorId) {
            if (sensorId == null || sensorId.isBlank()) {
                throw new IllegalArgumentException("sensorId must not be empty");
            }
            List<ColdChainTelemetryEntity> readings = coldChainRepository.findAll().stream()
                    .filter(r -> sensorId.trim().equalsIgnoreCase(r.getSensorId()))
                    .toList();

            if (readings.isEmpty()) {
                return new ColdChainStabilityReport(
                        sensorId, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        0, 0, "VVM_STAGE_I_OPTIMAL", false,
                        "No telemetry excursions recorded for sensor " + sensorId);
            }

            BigDecimal min = readings.get(0).getTemperatureCelsius();
            BigDecimal max = min;
            BigDecimal sum = BigDecimal.ZERO;
            int heatExcursions = 0;
            int freezeExcursions = 0;

            for (ColdChainTelemetryEntity r : readings) {
                BigDecimal t = r.getTemperatureCelsius();
                if (t == null) continue;
                if (t.compareTo(min) < 0) min = t;
                if (t.compareTo(max) > 0) max = t;
                sum = sum.add(t);
                if (t.compareTo(MAX_SAFE_TEMP) > 0) {
                    heatExcursions++;
                }
                if (t.compareTo(FREEZE_DAMAGE_TEMP) < 0) {
                    freezeExcursions++;
                }
            }

            BigDecimal mean = sum.divide(BigDecimal.valueOf(readings.size()), 2, RoundingMode.HALF_UP);
            String vvmStage;
            boolean quarantineRequired;
            String directive;

            if (freezeExcursions > 0) {
                vvmStage = "FREEZE_COMPROMISED";
                quarantineRequired = true;
                directive = "CRITICAL FREEZE EXCURSION (< 0.5°C): Quarantine aluminium-adjuvanted vaccines (Penta, PCV, Td, HepB) and perform WHO Shake Test.";
            } else if (heatExcursions >= 3 || max.compareTo(new BigDecimal("12.0")) > 0) {
                vvmStage = "VVM_STAGE_III_IV_DISCARD";
                quarantineRequired = true;
                directive = "CRITICAL HEAT EXCURSION (max " + max + "°C): VVM discard point reached. Quarantine all biologics in unit " + sensorId + ".";
            } else if (heatExcursions > 0) {
                vvmStage = "VVM_STAGE_II_USE_FIRST";
                quarantineRequired = false;
                directive = "Minor thermal excursion detected (max " + max + "°C). Prioritize stored lots on FEFO/VVM Stage II basis.";
            } else {
                vvmStage = "VVM_STAGE_I_OPTIMAL";
                quarantineRequired = false;
                directive = "Cold-chain unit maintaining strict 2°C–8°C WHO thermal envelope.";
            }

            return new ColdChainStabilityReport(
                    sensorId,
                    readings.size(),
                    min,
                    max,
                    mean,
                    heatExcursions,
                    freezeExcursions,
                    vvmStage,
                    quarantineRequired,
                    directive
            );
        }

        /**
         * Audits ISO 13485 biomedical preventive maintenance and calibration schedules
         * across a healthcare facility.
         */
        @Transactional(readOnly = true)
        @ClinicalAudit(action = "BIOMED_CALIBRATION_AUDIT", resourceType = "BiomedMaintenance")
        public BiomedicalComplianceSummary auditFacilityBiomedicalCompliance(UUID facilityId) {
            List<BiomedMaintenanceEntity> assets = biomedMaintenanceRepository.findAll().stream()
                    .filter(a -> facilityId == null || facilityId.equals(a.getFacilityId()))
                    .toList();

            LocalDate today = LocalDate.now();
            int compliant = 0;
            List<String> overdueTags = new ArrayList<>();

            for (BiomedMaintenanceEntity asset : assets) {
                boolean passed = asset.isCalibrationPassed();
                boolean notOverdue = asset.getNextPmDue() == null || !asset.getNextPmDue().isBefore(today);
                if (passed && notOverdue) {
                    compliant++;
                } else {
                    overdueTags.add(asset.getAssetTag() + " (" + asset.getEquipmentName() + ")");
                }
            }

            int total = assets.size();
            double pct = total == 0 ? 100.0 : Math.round((compliant * 10000.0) / total) / 100.0;
            return new BiomedicalComplianceSummary(
                    facilityId,
                    total,
                    compliant,
                    total - compliant,
                    pct,
                    List.copyOf(overdueTags)
            );
        }
    }

    @RestController
    @RequestMapping("/api/v1/biomedical-coldchain")
    @Tag(name = "Biomedical PM & 2°C-8°C Cold-Chain Governance", description = "WHO VVM thermal excursion analysis and ISO 13485 biomedical equipment calibration compliance")
    public static class BiomedicalColdChainGovernanceController {

        private final BiomedicalColdChainGovernanceService governanceService;

        public BiomedicalColdChainGovernanceController(BiomedicalColdChainGovernanceService governanceService) {
            this.governanceService = governanceService;
        }

        @GetMapping("/excursion-analysis/{sensorId}")
        @PreAuthorize("hasAuthority('PERM_biomed:manage') or hasAuthority('PERM_pharmacy:inventory')")
        @Operation(summary = "Analyze 2°C-8°C cold-chain telemetry and estimate WHO VVM stability stage")
        public ColdChainStabilityReport analyzeSensor(@PathVariable String sensorId) {
            return governanceService.analyzeSensorThermalStability(sensorId);
        }

        @GetMapping("/maintenance-compliance")
        @PreAuthorize("hasAuthority('PERM_biomed:manage') or hasAuthority('PERM_facility:manage')")
        @Operation(summary = "Audit ISO 13485 biomedical preventive maintenance and calibration compliance")
        public BiomedicalComplianceSummary getComplianceSummary(@RequestParam(required = false) UUID facilityId) {
            return governanceService.auditFacilityBiomedicalCompliance(facilityId);
        }
    }
}
