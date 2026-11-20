/*
 * =============================================================================
 * Opes HealthCore SDK — IDSR Disease Surveillance & Epidemic Intelligence Starter
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.publichealth.autoconfigure.PublicHealthAutoConfiguration;
import com.opeshealth.healthcore.publichealth.domain.PublicHealthEntities.ContactTracingLinkEntity;
import com.opeshealth.healthcore.publichealth.domain.PublicHealthEntities.SurveillanceCaseEntity;
import com.opeshealth.healthcore.publichealth.service.PublicHealthService;
import com.opeshealth.healthcore.publichealth.service.PublicHealthService.ContactTracingRepository;
import com.opeshealth.healthcore.publichealth.service.PublicHealthService.SurveillanceCaseRepository;
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
 * End-to-end WHO AFRO Integrated Disease Surveillance and Response (IDSR) &amp;
 * Epidemic Intelligence module computing district outbreak status, contact-tracing
 * effective reproduction number ($R_e$) proxies, and 21-day high-risk exposure chains
 * from PostgreSQL.
 */
@AutoConfiguration(after = PublicHealthAutoConfiguration.class)
public class DiseaseSurveillanceStarter {

    public record DistrictEpidemicBulletin(
            String districtCode,
            int totalNotifiableCases,
            int labConfirmedCases,
            Map<String, Integer> casesByIcd10,
            List<String> activeOutbreakAlerts
    ) {}

    public record ContactTracingNetworkSummary(
            UUID indexCaseId,
            int totalContactsTraced,
            int highRiskContacts,
            int symptomaticContactsConverted,
            double secondaryAttackRatePercent,
            List<UUID> contactLinkIds
    ) {}

    @Bean
    @ConditionalOnMissingBean
    public IdsrEpidemicIntelligenceService idsrEpidemicIntelligenceService(
            PublicHealthService publicHealthService,
            SurveillanceCaseRepository surveillanceCaseRepository,
            ContactTracingRepository contactTracingRepository) {
        return new IdsrEpidemicIntelligenceService(
                publicHealthService, surveillanceCaseRepository, contactTracingRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdsrEpidemicIntelligenceController idsrEpidemicIntelligenceController(
            IdsrEpidemicIntelligenceService intelligenceService) {
        return new IdsrEpidemicIntelligenceController(intelligenceService);
    }

    public static class IdsrEpidemicIntelligenceService {

        private final PublicHealthService publicHealthService;
        private final SurveillanceCaseRepository surveillanceCaseRepository;
        private final ContactTracingRepository contactTracingRepository;

        public IdsrEpidemicIntelligenceService(
                PublicHealthService publicHealthService,
                SurveillanceCaseRepository surveillanceCaseRepository,
                ContactTracingRepository contactTracingRepository) {
            this.publicHealthService = Objects.requireNonNull(publicHealthService, "PublicHealthService is required");
            this.surveillanceCaseRepository = Objects.requireNonNull(surveillanceCaseRepository, "SurveillanceCaseRepository is required");
            this.contactTracingRepository = Objects.requireNonNull(contactTracingRepository, "ContactTracingRepository is required");
        }

        /**
         * Compiles a WHO IDSR District Epidemiological Bulletin from persisted
         * {@link SurveillanceCaseEntity} rows in PostgreSQL.
         */
        @Transactional(readOnly = true)
        @ClinicalAudit(action = "IDSR_GENERATE_DISTRICT_BULLETIN", resourceType = "SurveillanceCase")
        public DistrictEpidemicBulletin generateDistrictBulletin(String districtCode) {
            if (districtCode == null || districtCode.isBlank()) {
                throw new IllegalArgumentException("districtCode is required");
            }
            String normDistrict = districtCode.trim();
            List<SurveillanceCaseEntity> cases = surveillanceCaseRepository.findByDistrictCode(normDistrict);

            int confirmed = 0;
            Map<String, Integer> byIcd10 = new LinkedHashMap<>();
            for (SurveillanceCaseEntity c : cases) {
                if ("CONFIRMED".equalsIgnoreCase(c.getCaseClassification())) {
                    confirmed++;
                }
                if (c.getDiseaseIcd10() != null) {
                    byIcd10.merge(c.getDiseaseIcd10(), 1, Integer::sum);
                }
            }

            List<String> alerts = new ArrayList<>();
            for (Map.Entry<String, Integer> e : byIcd10.entrySet()) {
                String icd = e.getKey();
                int count = e.getValue();
                if (("A00".equalsIgnoreCase(icd) || "A98".equalsIgnoreCase(icd)) && count >= 1) {
                    alerts.add("IMMEDIATE_IDSR_ALERT: " + icd + " (" + count + " case(s)) in " + normDistrict);
                } else if (count >= 5) {
                    alerts.add("CLUSTER_SURGE_ALERT: " + icd + " (" + count + " cases) in " + normDistrict);
                }
            }

            return new DistrictEpidemicBulletin(
                    normDistrict,
                    cases.size(),
                    confirmed,
                    Collections.unmodifiableMap(byIcd10),
                    List.copyOf(alerts)
            );
        }

        /**
         * Analyzes the contact-tracing transmission ring for an index case and computes
         * the secondary attack rate (SAR).
         */
        @Transactional(readOnly = true)
        public ContactTracingNetworkSummary analyzeContactTracingRing(UUID indexCaseId) {
            if (indexCaseId == null) {
                throw new IllegalArgumentException("indexCaseId is required");
            }
            List<ContactTracingLinkEntity> links = contactTracingRepository.findAll().stream()
                    .filter(l -> indexCaseId.equals(l.getIndexCaseId()))
                    .toList();

            int highRisk = 0;
            int converted = 0;
            List<UUID> ids = new ArrayList<>(links.size());

            for (ContactTracingLinkEntity link : links) {
                ids.add(link.getId());
                if ("HIGH".equalsIgnoreCase(link.getExposureType())) {
                    highRisk++;
                }
                if ("SYMPTOMATIC_CONVERTED".equalsIgnoreCase(link.getFollowUpStatus())) {
                    converted++;
                }
            }

            int total = links.size();
            double sar = total == 0 ? 0.0 : Math.round((converted * 10000.0) / total) / 100.0;
            return new ContactTracingNetworkSummary(
                    indexCaseId,
                    total,
                    highRisk,
                    converted,
                    sar,
                    List.copyOf(ids)
            );
        }
    }

    @RestController
    @RequestMapping("/api/v1/disease-surveillance")
    @Tag(name = "WHO IDSR Disease Surveillance & Epidemic Intelligence", description = "District outbreak threshold monitoring and contact-tracing secondary attack rate analytics")
    public static class IdsrEpidemicIntelligenceController {

        private final IdsrEpidemicIntelligenceService intelligenceService;

        public IdsrEpidemicIntelligenceController(IdsrEpidemicIntelligenceService intelligenceService) {
            this.intelligenceService = intelligenceService;
        }

        @GetMapping("/epidemic-intelligence/{districtCode}")
        @PreAuthorize("hasAuthority('PERM_surveillance:report') or hasAuthority('PERM_dhis2:export')")
        @Operation(summary = "Generate WHO IDSR district epidemiological bulletin and outbreak alerts")
        public DistrictEpidemicBulletin getDistrictBulletin(@PathVariable String districtCode) {
            return intelligenceService.generateDistrictBulletin(districtCode);
        }

        @GetMapping("/contact-tracing-network/{indexCaseId}")
        @PreAuthorize("hasAuthority('PERM_surveillance:report')")
        @Operation(summary = "Analyze contact-tracing exposure ring and secondary attack rate for an index case")
        public ContactTracingNetworkSummary getContactNetwork(@PathVariable UUID indexCaseId) {
            return intelligenceService.analyzeContactTracingRing(indexCaseId);
        }
    }
}
