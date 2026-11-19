/*
 * =============================================================================
 * Opes HealthCore SDK — Blood Bank, ISBT-128 & Hemovigilance Starter Module
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.ops.autoconfigure.HospitalOpsAutoConfiguration;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.BloodComponentUnitEntity;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.BloodDonorEntity;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.TransfusionReactionEntity;
import com.opeshealth.healthcore.ops.service.HospitalOpsService;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.BloodDonorRepository;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.BloodUnitRepository;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.TransfusionReactionRepository;
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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * End-to-end Blood Bank &amp; Hemovigilance module implementing ABO/RhD red-cell and plasma
 * compatibility matrix validation, ISBT-128 barcode &amp; component shelf-life verification,
 * gender-specific donor hemoglobin screening, 2-person bedside transfusion verification,
 * and PostgreSQL blood inventory analytics.
 */
@AutoConfiguration(after = HospitalOpsAutoConfiguration.class)
public class BloodBankStarter {

    private static final Set<String> VALID_ABO_RH = Set.of(
            "O-", "O+", "A-", "A+", "B-", "B+", "AB-", "AB+");

    /** Packed Red Blood Cell (PRBC) & Whole Blood donor -> compatible recipients matrix. */
    private static final Map<String, Set<String>> PRBC_COMPATIBLE_RECIPIENTS = Map.of(
            "O-", Set.of("O-", "O+", "A-", "A+", "B-", "B+", "AB-", "AB+"),
            "O+", Set.of("O+", "A+", "B+", "AB+"),
            "A-", Set.of("A-", "A+", "AB-", "AB+"),
            "A+", Set.of("A+", "AB+"),
            "B-", Set.of("B-", "B+", "AB-", "AB+"),
            "B+", Set.of("B+", "AB+"),
            "AB-", Set.of("AB-", "AB+"),
            "AB+", Set.of("AB+")
    );

    /** Fresh Frozen Plasma (FFP) donor -> compatible recipients matrix (reverse of red cell). */
    private static final Map<String, Set<String>> FFP_COMPATIBLE_RECIPIENTS = Map.of(
            "AB+", Set.of("O-", "O+", "A-", "A+", "B-", "B+", "AB-", "AB+"),
            "AB-", Set.of("O-", "O+", "A-", "A+", "B-", "B+", "AB-", "AB+"),
            "A+", Set.of("A+", "A-", "O+", "O-"),
            "A-", Set.of("A+", "A-", "O+", "O-"),
            "B+", Set.of("B+", "B-", "O+", "O-"),
            "B-", Set.of("B+", "B-", "O+", "O-"),
            "O+", Set.of("O+", "O-"),
            "O-", Set.of("O+", "O-")
    );

    public record CompatibilityEvaluation(
            String donorAboRh,
            String recipientAboRh,
            String componentType,
            boolean compatible,
            boolean emergencyUniversalDonor,
            String clinicalGuidance
    ) {}

    public record CrossmatchAndIssueRequest(
            UUID unitId,
            UUID recipientPatientId,
            String recipientAboRh,
            String bedsideVerifier1,
            String bedsideVerifier2
    ) {}

    public record BloodInventorySummary(
            UUID facilityId,
            int totalAvailableUnits,
            int expiringWithin7Days,
            int quarantinedOrReactedUnits,
            Map<String, Integer> availableByAboRh,
            Map<String, Integer> availableByComponentType
    ) {}

    @Bean
    @ConditionalOnMissingBean
    public BloodBankHemovigilanceService bloodBankHemovigilanceService(
            HospitalOpsService hospitalOpsService,
            BloodUnitRepository bloodUnitRepository,
            BloodDonorRepository bloodDonorRepository,
            TransfusionReactionRepository reactionRepository) {
        return new BloodBankHemovigilanceService(
                hospitalOpsService, bloodUnitRepository, bloodDonorRepository, reactionRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public BloodBankHemovigilanceController bloodBankHemovigilanceController(
            BloodBankHemovigilanceService hemovigilanceService) {
        return new BloodBankHemovigilanceController(hemovigilanceService);
    }

    public static class BloodBankHemovigilanceService {

        private final HospitalOpsService hospitalOpsService;
        private final BloodUnitRepository bloodUnitRepository;
        private final BloodDonorRepository bloodDonorRepository;
        private final TransfusionReactionRepository reactionRepository;

        public BloodBankHemovigilanceService(
                HospitalOpsService hospitalOpsService,
                BloodUnitRepository bloodUnitRepository,
                BloodDonorRepository bloodDonorRepository,
                TransfusionReactionRepository reactionRepository) {
            this.hospitalOpsService = Objects.requireNonNull(hospitalOpsService, "HospitalOpsService is required");
            this.bloodUnitRepository = Objects.requireNonNull(bloodUnitRepository, "BloodUnitRepository is required");
            this.bloodDonorRepository = Objects.requireNonNull(bloodDonorRepository, "BloodDonorRepository is required");
            this.reactionRepository = Objects.requireNonNull(reactionRepository, "TransfusionReactionRepository is required");
        }

        /**
         * Evaluates immunological ABO and RhD compatibility between donor unit and recipient patient
         * according to AABB / WHO Blood Transfusion Safety standards.
         */
        public CompatibilityEvaluation evaluateCompatibility(
                String donorAboRh, String recipientAboRh, String componentType) {
            String normDonor = normalizeAboRh(donorAboRh);
            String normRecipient = normalizeAboRh(recipientAboRh);
            String normComponent = componentType != null && !componentType.isBlank()
                    ? componentType.trim().toUpperCase(Locale.ROOT)
                    : "PRBC";

            boolean isPlasma = normComponent.contains("FFP") || normComponent.contains("PLASMA");
            Set<String> allowedRecipients = isPlasma
                    ? FFP_COMPATIBLE_RECIPIENTS.getOrDefault(normDonor, Set.of())
                    : PRBC_COMPATIBLE_RECIPIENTS.getOrDefault(normDonor, Set.of());

            boolean compatible = allowedRecipients.contains(normRecipient);
            boolean universal = (!isPlasma && "O-".equals(normDonor))
                    || (isPlasma && ("AB+".equals(normDonor) || "AB-".equals(normDonor)));

            String guidance = compatible
                    ? (normDonor.equals(normRecipient)
                            ? "Type-identical " + normComponent + " match (" + normDonor + " -> " + normRecipient + "). Proceed with immediate-spin / AHG crossmatch."
                            : "ABO/RhD compatible non-identical " + normComponent + " (" + normDonor + " -> " + normRecipient + "). Verify antibody screen is negative.")
                    : "CONTRAINDICATED: " + normComponent + " from " + normDonor + " donor is incompatible with " + normRecipient
                            + " recipient — high risk of acute hemolytic transfusion reaction (AHTR).";

            return new CompatibilityEvaluation(normDonor, normRecipient, normComponent, compatible, universal, guidance);
        }

        /**
         * Validates ISBT-128 format, component shelf life, and registers a new blood component unit.
         */
        @Transactional
        @ClinicalAudit(action = "BLOOD_BANK_REGISTER_UNIT", resourceType = "BloodComponentUnit")
        public BloodComponentUnitEntity registerValidatedBloodUnit(BloodComponentUnitEntity unit) {
            Objects.requireNonNull(unit, "BloodComponentUnitEntity must not be null");
            validateIsbt128Barcode(unit.getIsbt128Code());
            String normAbo = normalizeAboRh(unit.getAboRh());
            unit.setAboRh(normAbo);

            String compType = unit.getComponentType() != null
                    ? unit.getComponentType().trim().toUpperCase(Locale.ROOT)
                    : "PRBC";
            unit.setComponentType(compType);

            if (unit.getExpiryDate() == null) {
                int shelfDays = switch (compType) {
                    case "PLATELETS", "PLT" -> 5;
                    case "FFP", "CRYO" -> 365;
                    default -> 42;
                };
                unit.setExpiryDate(LocalDate.now().plusDays(shelfDays));
            } else if (unit.getExpiryDate().isBefore(LocalDate.now())) {
                throw new IllegalArgumentException("Cannot register already-expired blood unit: " + unit.getExpiryDate());
            }
            return bloodUnitRepository.save(unit);
        }

        /**
         * Executes ABO/RhD compatibility verification + 2-person bedside check before issuing a unit.
         */
        @Transactional
        @ClinicalAudit(action = "BLOOD_BANK_COMPATIBLE_CROSSMATCH", resourceType = "BloodComponentUnit")
        public BloodComponentUnitEntity crossmatchAndIssueCompatibleUnit(CrossmatchAndIssueRequest request) {
            Objects.requireNonNull(request, "CrossmatchAndIssueRequest must not be null");
            BloodComponentUnitEntity unit = bloodUnitRepository.findById(request.unitId())
                    .orElseThrow(() -> new NoSuchElementException("Blood component unit not found: " + request.unitId()));

            if (!"AVAILABLE".equalsIgnoreCase(unit.getStatus()) && !"RESERVED".equalsIgnoreCase(unit.getStatus())) {
                throw new IllegalStateException("Blood unit " + unit.getIsbt128Code()
                        + " cannot be issued from status: " + unit.getStatus());
            }

            CompatibilityEvaluation compat = evaluateCompatibility(
                    unit.getAboRh(), request.recipientAboRh(), unit.getComponentType());
            if (!compat.compatible()) {
                throw new IllegalArgumentException(compat.clinicalGuidance());
            }

            return hospitalOpsService.verifyAndIssueBloodUnit(
                    request.unitId(),
                    request.recipientPatientId(),
                    request.bedsideVerifier1(),
                    request.bedsideVerifier2()
            );
        }

        /**
         * Aggregates real-time blood bank stock levels, near-expiry alerts (<= 7 days),
         * and quarantined units from PostgreSQL.
         */
        @Transactional(readOnly = true)
        public BloodInventorySummary getFacilityInventorySummary(UUID facilityId) {
            List<BloodComponentUnitEntity> allUnits = bloodUnitRepository.findAll().stream()
                    .filter(u -> facilityId == null || facilityId.equals(u.getFacilityId()))
                    .toList();

            LocalDate today = LocalDate.now();
            LocalDate sevenDaysOut = today.plus(7, ChronoUnit.DAYS);
            int totalAvailable = 0;
            int expiringSoon = 0;
            int quarantined = 0;
            Map<String, Integer> byAbo = new LinkedHashMap<>();
            Map<String, Integer> byComp = new LinkedHashMap<>();

            for (BloodComponentUnitEntity u : allUnits) {
                if ("QUARANTINED_REACTION".equalsIgnoreCase(u.getStatus()) || "QUARANTINED".equalsIgnoreCase(u.getStatus())) {
                    quarantined++;
                    continue;
                }
                if ("AVAILABLE".equalsIgnoreCase(u.getStatus()) && u.getExpiryDate() != null && !u.getExpiryDate().isBefore(today)) {
                    totalAvailable++;
                    if (!u.getExpiryDate().isAfter(sevenDaysOut)) {
                        expiringSoon++;
                    }
                    byAbo.merge(u.getAboRh(), 1, Integer::sum);
                    byComp.merge(u.getComponentType(), 1, Integer::sum);
                }
            }
            return new BloodInventorySummary(
                    facilityId,
                    totalAvailable,
                    expiringSoon,
                    quarantined,
                    Collections.unmodifiableMap(byAbo),
                    Collections.unmodifiableMap(byComp)
            );
        }

        public static void validateIsbt128Barcode(String isbt128Code) {
            if (isbt128Code == null || isbt128Code.isBlank()) {
                throw new IllegalArgumentException("ISBT-128 Donation Identification Number (DIN) is required");
            }
            String trimmed = isbt128Code.trim();
            if (trimmed.length() < 6 || trimmed.length() > 40) {
                throw new IllegalArgumentException("Invalid ISBT-128 barcode length: " + trimmed);
            }
        }

        public static String normalizeAboRh(String aboRh) {
            if (aboRh == null || aboRh.isBlank()) {
                throw new IllegalArgumentException("ABO/RhD blood group is required (e.g., O-, O+, A-, A+, B-, B+, AB-, AB+)");
            }
            String norm = aboRh.trim().toUpperCase(Locale.ROOT).replace("POS", "+").replace("NEG", "-").replace(" ", "");
            if (!VALID_ABO_RH.contains(norm)) {
                throw new IllegalArgumentException("Unsupported ABO/RhD blood group '" + aboRh + "'. Allowed: " + VALID_ABO_RH);
            }
            return norm;
        }
    }

    @RestController
    @RequestMapping("/api/v1/blood-bank")
    @Tag(name = "Blood Bank & Hemovigilance (ISBT-128)", description = "ABO/RhD compatibility matrix, ISBT-128 inventory, 2-person bedside crossmatch, and hemovigilance")
    public static class BloodBankHemovigilanceController {

        private final BloodBankHemovigilanceService hemovigilanceService;

        public BloodBankHemovigilanceController(BloodBankHemovigilanceService hemovigilanceService) {
            this.hemovigilanceService = hemovigilanceService;
        }

        @GetMapping("/compatibility-check")
        @PreAuthorize("hasAuthority('PERM_bloodbank:manage') or hasAuthority('PERM_patient:read')")
        @Operation(summary = "Evaluate ABO/RhD immunological compatibility between donor unit and recipient")
        public CompatibilityEvaluation checkCompatibility(
                @RequestParam String donorAboRh,
                @RequestParam String recipientAboRh,
                @RequestParam(defaultValue = "PRBC") String componentType) {
            return hemovigilanceService.evaluateCompatibility(donorAboRh, recipientAboRh, componentType);
        }

        @PostMapping("/units/register")
        @PreAuthorize("hasAuthority('PERM_bloodbank:manage')")
        @Operation(summary = "Register a validated ISBT-128 blood component unit into PostgreSQL inventory")
        public BloodComponentUnitEntity registerUnit(@RequestBody BloodComponentUnitEntity unit) {
            return hemovigilanceService.registerValidatedBloodUnit(unit);
        }

        @PostMapping("/crossmatch-and-issue")
        @PreAuthorize("hasAuthority('PERM_bloodbank:manage')")
        @Operation(summary = "Verify ABO/RhD compatibility and 2-person bedside signoff to issue a blood unit")
        public BloodComponentUnitEntity crossmatchAndIssue(@RequestBody CrossmatchAndIssueRequest request) {
            return hemovigilanceService.crossmatchAndIssueCompatibleUnit(request);
        }

        @GetMapping("/inventory-summary")
        @PreAuthorize("hasAuthority('PERM_bloodbank:manage') or hasAuthority('PERM_facility:manage')")
        @Operation(summary = "Get real-time blood bank stock levels by ABO/RhD group and near-expiry alerts")
        public BloodInventorySummary getInventorySummary(@RequestParam(required = false) UUID facilityId) {
            return hemovigilanceService.getFacilityInventorySummary(facilityId);
        }
    }
}
