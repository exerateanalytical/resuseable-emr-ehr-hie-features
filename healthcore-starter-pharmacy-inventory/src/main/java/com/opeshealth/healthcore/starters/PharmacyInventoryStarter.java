/*
 * =============================================================================
 * Opes HealthCore SDK — Standalone Pharmacy FEFO & Narcotics Ledger Starter
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.pharmacylis.autoconfigure.PharmacyLisAutoConfiguration;
import com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities.MedicationBatchEntity;
import com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities.PharmacyDispenseEntity;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService.MedicationBatchRepository;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService.PharmacyDispenseRepository;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.springframework.security.access.AccessDeniedException;
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
 * End-to-end Pharmacy Information System (PIS) &amp; Inventory Governance module
 * implementing FEFO (First-Expired, First-Out) stock health analytics, 30/60/90-day
 * near-expiry quarantine forecasting, and Schedule II/III controlled substance
 * dual-witness chain-of-custody verification in PostgreSQL.
 */
@AutoConfiguration(after = PharmacyLisAutoConfiguration.class)
public class PharmacyInventoryStarter {

    public record NearExpiryBatchAlert(
            UUID batchId,
            String drugCode,
            String batchNumber,
            LocalDate expiryDate,
            long daysUntilExpiry,
            int quantityRemaining,
            String severityTier
    ) {}

    public record FacilityPharmacyStockHealth(
            UUID facilityId,
            int totalActiveBatches,
            int totalQuarantinedBatches,
            int zeroStockOutDrugCount,
            Map<String, Integer> availableUnitsByDrugCode,
            List<NearExpiryBatchAlert> nearExpiryAlerts
    ) {}

    @Bean
    @ConditionalOnMissingBean
    public PharmacyFefoAndColdStockGovernanceService pharmacyFefoAndColdStockGovernanceService(
            PharmacyLisService pharmacyLisService,
            MedicationBatchRepository medicationBatchRepository,
            PharmacyDispenseRepository pharmacyDispenseRepository) {
        return new PharmacyFefoAndColdStockGovernanceService(
                pharmacyLisService, medicationBatchRepository, pharmacyDispenseRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public PharmacyFefoGovernanceController pharmacyFefoGovernanceController(
            PharmacyFefoAndColdStockGovernanceService governanceService) {
        return new PharmacyFefoGovernanceController(governanceService);
    }

    public static class PharmacyFefoAndColdStockGovernanceService {

        private final PharmacyLisService pharmacyLisService;
        private final MedicationBatchRepository medicationBatchRepository;
        private final PharmacyDispenseRepository pharmacyDispenseRepository;

        public PharmacyFefoAndColdStockGovernanceService(
                PharmacyLisService pharmacyLisService,
                MedicationBatchRepository medicationBatchRepository,
                PharmacyDispenseRepository pharmacyDispenseRepository) {
            this.pharmacyLisService = Objects.requireNonNull(pharmacyLisService, "PharmacyLisService is required");
            this.medicationBatchRepository = Objects.requireNonNull(medicationBatchRepository, "MedicationBatchRepository is required");
            this.pharmacyDispenseRepository = Objects.requireNonNull(pharmacyDispenseRepository, "PharmacyDispenseRepository is required");
        }

        private static UUID requireFacilityScope(UUID requestedFacilityId) {
            UUID active = FacilityContext.getFacilityId().orElse(null);
            if (active != null && requestedFacilityId != null && !active.equals(requestedFacilityId)) {
                throw new AccessDeniedException("Cross-facility pharmacy inventory access denied");
            }
            if (requestedFacilityId == null && active == null) {
                throw new AccessDeniedException("Facility scope is required for pharmacy inventory access");
            }
            return requestedFacilityId != null ? requestedFacilityId : active;
        }

        /**
         * Analyzes facility medication stock batches in PostgreSQL (`hc_medication_batches`)
         * to compute available FEFO quantities by RxNorm/ATC code, identify stock-outs, and
         * generate 30/60/90-day near-expiry warnings.
         */
        @Transactional(readOnly = true)
        @ClinicalAudit(action = "PHARMACY_STOCK_HEALTH_AUDIT", resourceType = "MedicationBatch")
        public FacilityPharmacyStockHealth evaluateFacilityStockHealth(UUID facilityId, int horizonDays) {
            int windowDays = horizonDays > 0 ? horizonDays : 90;
            LocalDate today = LocalDate.now();
            LocalDate horizonDate = today.plusDays(windowDays);

            UUID checkedFacilityId = requireFacilityScope(facilityId);
            List<MedicationBatchEntity> batches = medicationBatchRepository.findByFacilityId(checkedFacilityId);

            int activeBatches = 0;
            int quarantinedBatches = 0;
            Map<String, Integer> stockByDrug = new LinkedHashMap<>();
            List<NearExpiryBatchAlert> alerts = new ArrayList<>();

            for (MedicationBatchEntity b : batches) {
                stockByDrug.putIfAbsent(b.getDrugCode(), 0);
                if (b.isQuarantined() || (b.getExpiryDate() != null && !b.getExpiryDate().isAfter(today))) {
                    quarantinedBatches++;
                    continue;
                }
                activeBatches++;
                stockByDrug.merge(b.getDrugCode(), Math.max(0, b.getQuantityOnHand()), Integer::sum);

                if (b.getExpiryDate() != null && !b.getExpiryDate().isAfter(horizonDate) && b.getQuantityOnHand() > 0) {
                    long daysLeft = ChronoUnit.DAYS.between(today, b.getExpiryDate());
                    String tier = daysLeft <= 30 ? "CRITICAL_30_DAY" : (daysLeft <= 60 ? "HIGH_60_DAY" : "ADVISORY_90_DAY");
                    alerts.add(new NearExpiryBatchAlert(
                            b.getId(),
                            b.getDrugCode(),
                            b.getLotNumber(),
                            b.getExpiryDate(),
                            daysLeft,
                            b.getQuantityOnHand(),
                            tier
                    ));
                }
            }

            alerts.sort(Comparator.comparingLong(NearExpiryBatchAlert::daysUntilExpiry));
            int stockOuts = (int) stockByDrug.values().stream().filter(qty -> qty <= 0).count();

            return new FacilityPharmacyStockHealth(
                    facilityId,
                    activeBatches,
                    quarantinedBatches,
                    stockOuts,
                    Collections.unmodifiableMap(stockByDrug),
                    List.copyOf(alerts)
            );
        }

        /**
         * Retrieves the controlled-substance dual-witness audit ledger from PostgreSQL
         * (`hc_pharmacy_dispenses`) where a second witness signature was required.
         */
        @Transactional(readOnly = true)
        public List<PharmacyDispenseEntity> getControlledSubstanceDispenseLedger(UUID facilityId) {
            UUID checkedFacilityId = requireFacilityScope(facilityId);
            return pharmacyDispenseRepository.findByFacilityId(checkedFacilityId).stream()
                    .filter(d -> d.getControlledWitnessId() != null
                            && !d.getControlledWitnessId().isBlank())
                    .toList();
        }
    }

    @RestController
    @RequestMapping("/api/v1/pharmacy-inventory")
    @Tag(name = "Pharmacy FEFO Stock Health & Narcotics Ledger", description = "FEFO stock-out analysis, 30/60/90-day near-expiry alerts, and controlled substance dual-witness ledger")
    public static class PharmacyFefoGovernanceController {

        private final PharmacyFefoAndColdStockGovernanceService governanceService;

        public PharmacyFefoGovernanceController(PharmacyFefoAndColdStockGovernanceService governanceService) {
            this.governanceService = governanceService;
        }

        @GetMapping("/stock-health")
        @PreAuthorize("hasAuthority('PERM_pharmacy:inventory') or hasAuthority('PERM_pharmacy:dispense')")
        @Operation(summary = "Analyze facility FEFO medication stock levels, stock-outs, and near-expiry lots")
        public FacilityPharmacyStockHealth getStockHealth(
                @RequestParam(required = false) UUID facilityId,
                @RequestParam(defaultValue = "90") int horizonDays) {
            UUID authorizedFacilityId = FacilityContext.requireFacilityId();
            if (facilityId != null && !authorizedFacilityId.equals(facilityId)) {
                throw new AccessDeniedException("Cross-facility pharmacy inventory access denied");
            }
            return governanceService.evaluateFacilityStockHealth(authorizedFacilityId, horizonDays);
        }

        @GetMapping("/controlled-substance-ledger")
        @PreAuthorize("hasAuthority('PERM_pharmacy:controlled:witness') or hasAuthority('PERM_audit:read')")
        @Operation(summary = "Retrieve dual-witness Schedule II/III controlled substance dispense ledger")
        public List<PharmacyDispenseEntity> getControlledLedger(@RequestParam(required = false) UUID facilityId) {
            UUID authorizedFacilityId = FacilityContext.requireFacilityId();
            if (facilityId != null && !authorizedFacilityId.equals(facilityId)) {
                throw new AccessDeniedException("Cross-facility controlled-substance ledger access denied");
            }
            return governanceService.getControlledSubstanceDispenseLedger(authorizedFacilityId);
        }
    }
}
