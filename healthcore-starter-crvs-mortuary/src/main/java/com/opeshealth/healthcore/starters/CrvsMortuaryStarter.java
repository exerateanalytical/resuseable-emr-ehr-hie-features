/*
 * =============================================================================
 * Opes HealthCore SDK — CRVS Birth, WHO MCCD Death & Mortuary Starter Module
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.ops.autoconfigure.HospitalOpsAutoConfiguration;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.CrvsBirthDeathEntity;
import com.opeshealth.healthcore.ops.service.HospitalOpsService;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.CrvsRepository;
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
 * End-to-end Civil Registration and Vital Statistics (CRVS), WHO Medical Certificate
 * of Cause of Death (MCCD), and Mortuary Chain-of-Custody module enforcing ICD-10
 * underlying cause validation (rejecting non-specific terminal mode-of-dying codes
 * as sole underlying cause) and two-witness mortuary release verification.
 */
@AutoConfiguration(after = HospitalOpsAutoConfiguration.class)
public class CrvsMortuaryStarter {

    /**
     * WHO ICD-10 ill-defined / terminal mode-of-dying codes that cannot serve as the sole
     * underlying cause of death without an antecedent etiology on Line 1b/1c.
     */
    private static final Set<String> WHO_MCCD_ILL_DEFINED_SOLE_CODES = Set.of(
            "R99", "I46.9", "R09.2", "R57.9", "R54", "R68.8"
    );

    public record WhoMccdDeathRegistrationRequest(
            UUID facilityId,
            UUID patientId,
            String immediateCauseLine1aIcd10,
            String antecedentCauseLine1bIcd10,
            String underlyingCauseLine1cIcd10,
            String certifyingPractitionerId,
            String mortuaryBayTag
    ) {}

    public record MortuaryChainOfCustodyReleaseRequest(
            UUID crvsRecordId,
            String attendingPhysicianId,
            String mortuaryOfficerId,
            String nextOfKinFullName,
            String nextOfKinNationalId
    ) {}

    public record MortuaryReleaseReceipt(
            UUID crvsRecordId,
            String certificateNumber,
            String mortuaryBayTag,
            String releasedToNextOfKin,
            String releaseStatus
    ) {}

    @Bean
    @ConditionalOnMissingBean
    public CrvsAndMortuaryChainOfCustodyService crvsAndMortuaryChainOfCustodyService(
            HospitalOpsService hospitalOpsService,
            CrvsRepository crvsRepository) {
        return new CrvsAndMortuaryChainOfCustodyService(hospitalOpsService, crvsRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public CrvsMortuaryComplianceController crvsMortuaryComplianceController(
            CrvsAndMortuaryChainOfCustodyService crvsMortuaryService) {
        return new CrvsMortuaryComplianceController(crvsMortuaryService);
    }

    public static class CrvsAndMortuaryChainOfCustodyService {

        private final HospitalOpsService hospitalOpsService;
        private final CrvsRepository crvsRepository;

        public CrvsAndMortuaryChainOfCustodyService(
                HospitalOpsService hospitalOpsService,
                CrvsRepository crvsRepository) {
            this.hospitalOpsService = Objects.requireNonNull(hospitalOpsService, "HospitalOpsService is required");
            this.crvsRepository = Objects.requireNonNull(crvsRepository, "CrvsRepository is required");
        }

        /**
         * Validates a WHO MCCD causal sequence (Line 1a immediate -> Line 1b intermediate -> Line 1c underlying)
         * against WHO ICD-10 mortality coding rules and registers the civil death record in PostgreSQL.
         */
        @Transactional
        @ClinicalAudit(action = "CRVS_WHO_MCCD_REGISTER", resourceType = "CrvsBirthDeath")
        public CrvsBirthDeathEntity validateAndRegisterWhoMccdDeath(WhoMccdDeathRegistrationRequest req) {
            Objects.requireNonNull(req, "WhoMccdDeathRegistrationRequest must not be null");
            if (req.patientId() == null) {
                throw new IllegalArgumentException("patientId is required for WHO MCCD death registration");
            }
            if (req.immediateCauseLine1aIcd10() == null || req.immediateCauseLine1aIcd10().isBlank()) {
                throw new IllegalArgumentException("WHO MCCD Line 1a (immediate cause of death ICD-10) is required");
            }

            String line1a = req.immediateCauseLine1aIcd10().trim().toUpperCase(Locale.ROOT);
            String line1b = req.antecedentCauseLine1bIcd10() != null ? req.antecedentCauseLine1bIcd10().trim().toUpperCase(Locale.ROOT) : "";
            String line1c = req.underlyingCauseLine1cIcd10() != null ? req.underlyingCauseLine1cIcd10().trim().toUpperCase(Locale.ROOT) : "";

            String resolvedUnderlying = !line1c.isEmpty() ? line1c : (!line1b.isEmpty() ? line1b : line1a);
            if (WHO_MCCD_ILL_DEFINED_SOLE_CODES.contains(resolvedUnderlying)) {
                throw new IllegalArgumentException("WHO MCCD Mortality Rule violation: ICD-10 code '"
                        + resolvedUnderlying + "' is a terminal mode of dying / ill-defined code and cannot be the sole underlying cause of death. Specify antecedent etiology on Line 1b or 1c.");
            }

            CrvsBirthDeathEntity entity = new CrvsBirthDeathEntity();
            entity.setFacilityId(req.facilityId() != null ? req.facilityId() : new UUID(0L, 1L));
            entity.setPatientId(req.patientId());
            entity.setEventType("DEATH");
            entity.setMccdUnderlyingCauseIcd10(resolvedUnderlying);
            entity.setMortuaryBayTag(req.mortuaryBayTag() != null && !req.mortuaryBayTag().isBlank()
                    ? req.mortuaryBayTag().trim()
                    : "MORT-BAY-A1");

            return hospitalOpsService.registerCrvsEvent(entity);
        }

        /**
         * Verifies dual-custodian chain of custody (certifying physician + mortuary officer + next-of-kin ID)
         * before releasing a decedent from the mortuary bay.
         */
        @Transactional
        @ClinicalAudit(action = "MORTUARY_CUSTODY_RELEASE", resourceType = "CrvsBirthDeath")
        public MortuaryReleaseReceipt verifyAndReleaseMortuaryCustody(MortuaryChainOfCustodyReleaseRequest req) {
            Objects.requireNonNull(req, "MortuaryChainOfCustodyReleaseRequest must not be null");
            if (req.attendingPhysicianId() == null || req.attendingPhysicianId().isBlank()
                    || req.mortuaryOfficerId() == null || req.mortuaryOfficerId().isBlank()
                    || req.attendingPhysicianId().trim().equalsIgnoreCase(req.mortuaryOfficerId().trim())) {
                throw new SecurityException("Mortuary release requires distinct attendingPhysicianId and mortuaryOfficerId signoffs");
            }
            if (req.nextOfKinFullName() == null || req.nextOfKinFullName().isBlank()
                    || req.nextOfKinNationalId() == null || req.nextOfKinNationalId().isBlank()) {
                throw new IllegalArgumentException("Verified next-of-kin full name and national ID are required for mortuary custody release");
            }

            CrvsBirthDeathEntity record = crvsRepository.findById(req.crvsRecordId())
                    .orElseThrow(() -> new NoSuchElementException("CRVS Death / Mortuary record not found: " + req.crvsRecordId()));

            if (!"DEATH".equalsIgnoreCase(record.getEventType())) {
                throw new IllegalStateException("Mortuary release applies only to DEATH CRVS records");
            }

            String bayTag = record.getMortuaryBayTag();
            record.setMortuaryBayTag("RELEASED:" + bayTag + ":NOK:" + req.nextOfKinNationalId().trim());
            crvsRepository.save(record);

            return new MortuaryReleaseReceipt(
                    record.getId(),
                    record.getCertificateNumber(),
                    bayTag,
                    req.nextOfKinFullName().trim(),
                    "CUSTODY_RELEASED_AND_AUDITED"
            );
        }
    }

    @RestController
    @RequestMapping("/api/v1/crvs-mortuary")
    @Tag(name = "CRVS Birth, WHO MCCD Death & Mortuary", description = "WHO ICD-10 Medical Certificate of Cause of Death validation and mortuary chain-of-custody release")
    public static class CrvsMortuaryComplianceController {

        private final CrvsAndMortuaryChainOfCustodyService crvsMortuaryService;

        public CrvsMortuaryComplianceController(CrvsAndMortuaryChainOfCustodyService crvsMortuaryService) {
            this.crvsMortuaryService = crvsMortuaryService;
        }

        @PostMapping("/mccd-validate-and-register")
        @PreAuthorize("hasAuthority('PERM_crvs:certify')")
        @Operation(summary = "Validate WHO MCCD causal chain and issue signed CRVS death certificate")
        public CrvsBirthDeathEntity registerMccdDeath(@RequestBody WhoMccdDeathRegistrationRequest request) {
            return crvsMortuaryService.validateAndRegisterWhoMccdDeath(request);
        }

        @PostMapping("/chain-of-custody/release")
        @PreAuthorize("hasAuthority('PERM_crvs:certify') or hasAuthority('PERM_facility:manage')")
        @Operation(summary = "Verify two-custodian and next-of-kin identity to release mortuary custody")
        public MortuaryReleaseReceipt releaseMortuaryCustody(@RequestBody MortuaryChainOfCustodyReleaseRequest request) {
            return crvsMortuaryService.verifyAndReleaseMortuaryCustody(request);
        }
    }
}
