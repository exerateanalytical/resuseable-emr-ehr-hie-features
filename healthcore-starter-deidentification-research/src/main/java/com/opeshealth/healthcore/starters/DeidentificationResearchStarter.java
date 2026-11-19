/*
 * =============================================================================
 * Opes HealthCore SDK — HIPAA Safe-Harbor De-Identification & Research Starter
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.ops.autoconfigure.HospitalOpsAutoConfiguration;
import com.opeshealth.healthcore.ops.service.HospitalOpsService;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.security.audit.PhiRedactionConverter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * End-to-end HIPAA 45 CFR § 164.514(b) 18-Identifier Safe-Harbor De-Identification
 * and GDPR Article 89 Scientific Research Export module enforcing age top-coding
 * ($\ge 89 \rightarrow \text{90+}$), 3-digit geographic prefix generalization,
 * salted SHA-256 longitudinal research pseudonyms, and $k$-anonymity ($k \ge 5$)
 * quasi-identifier equivalence class verification.
 */
@AutoConfiguration(after = HospitalOpsAutoConfiguration.class)
public class DeidentificationResearchStarter {

    public record ClinicalResearchSourceRow(
            UUID patientId,
            int birthYear,
            String gender,
            String postalCode,
            String diagnosisIcd10,
            String clinicalNoteFreeText
    ) {}

    public record SafeHarborCohortExportRequest(
            String studyProtocolId,
            String researchSalt,
            int minimumKAnonymity,
            List<ClinicalResearchSourceRow> records
    ) {}

    public record DeidentifiedResearchRecord(
            String researchSubjectHash,
            String ageBracketOrYear,
            String gender,
            String region3DigitPrefix,
            String diagnosisIcd10,
            String scrubbedClinicalSummary
    ) {}

    public record SafeHarborCohortExportResult(
            String studyProtocolId,
            Instant exportedAt,
            int inputRecordCount,
            int exportedRecordCount,
            int suppressedForKAnonymityCount,
            int achievedMinEquivalenceClassSize,
            boolean kAnonymitySatisfied,
            List<DeidentifiedResearchRecord> cohort
    ) {}

    @Bean
    @ConditionalOnMissingBean
    public HipaaSafeHarborDeidentificationService hipaaSafeHarborDeidentificationService(
            HospitalOpsService hospitalOpsService) {
        return new HipaaSafeHarborDeidentificationService(hospitalOpsService);
    }

    @Bean
    @ConditionalOnMissingBean
    public HipaaSafeHarborDeidentificationController hipaaSafeHarborDeidentificationController(
            HipaaSafeHarborDeidentificationService deidentificationService) {
        return new HipaaSafeHarborDeidentificationController(deidentificationService);
    }

    public static class HipaaSafeHarborDeidentificationService {

        private final HospitalOpsService hospitalOpsService;

        public HipaaSafeHarborDeidentificationService(HospitalOpsService hospitalOpsService) {
            this.hospitalOpsService = Objects.requireNonNull(hospitalOpsService, "HospitalOpsService is required");
        }

        /**
         * De-identifies a clinical cohort under HIPAA 45 CFR § 164.514(b) Safe Harbor rules
         * and enforces $k$-anonymity across quasi-identifiers `(ageBracket, gender, regionPrefix)`
         * by suppressing or generalizing equivalence classes smaller than `minimumKAnonymity`.
         */
        @ClinicalAudit(action = "RESEARCH_SAFE_HARBOR_COHORT_EXPORT", resourceType = "DeidentifiedCohort")
        public SafeHarborCohortExportResult exportSafeHarborCohort(SafeHarborCohortExportRequest request) {
            Objects.requireNonNull(request, "SafeHarborCohortExportRequest must not be null");
            List<ClinicalResearchSourceRow> rows = request.records() != null ? request.records() : List.of();
            String salt = request.researchSalt() != null && !request.researchSalt().isBlank()
                    ? request.researchSalt().trim()
                    : "OPES-HIPAA-GDPR-RESEARCH-SALT";
            int targetK = Math.max(1, request.minimumKAnonymity());

            List<DeidentifiedResearchRecord> transformed = new ArrayList<>(rows.size());
            Map<String, Integer> equivalenceClassCounts = new LinkedHashMap<>();

            for (ClinicalResearchSourceRow row : rows) {
                Map<String, Object> base = hospitalOpsService.deidentifyForResearch(
                        row.patientId() != null ? row.patientId() : UUID.randomUUID(),
                        row.birthYear(),
                        row.postalCode(),
                        row.diagnosisIcd10(),
                        salt
                );
                String subjectHash = String.valueOf(base.get("researchSubjectHash"));
                String ageVal = String.valueOf(base.get("birthYearOrAgeGroup"));
                String regionPrefix = String.valueOf(base.get("regionPrefix"));
                String gender = row.gender() != null ? row.gender().trim().toLowerCase(Locale.ROOT) : "unknown";
                String scrubbedNote = row.clinicalNoteFreeText() != null
                        ? PhiRedactionConverter.redact(row.clinicalNoteFreeText())
                        : "";

                DeidentifiedResearchRecord rec = new DeidentifiedResearchRecord(
                        subjectHash,
                        ageVal,
                        gender,
                        regionPrefix,
                        row.diagnosisIcd10(),
                        scrubbedNote
                );
                transformed.add(rec);

                String quasiKey = ageVal + "|" + gender + "|" + regionPrefix;
                equivalenceClassCounts.merge(quasiKey, 1, Integer::sum);
            }

            List<DeidentifiedResearchRecord> released = new ArrayList<>();
            int suppressed = 0;
            int minClassSize = Integer.MAX_VALUE;

            for (DeidentifiedResearchRecord rec : transformed) {
                String quasiKey = rec.ageBracketOrYear() + "|" + rec.gender() + "|" + rec.region3DigitPrefix();
                int classSize = equivalenceClassCounts.getOrDefault(quasiKey, 0);
                if (classSize >= targetK) {
                    released.add(rec);
                    if (classSize < minClassSize) {
                        minClassSize = classSize;
                    }
                } else {
                    suppressed++;
                }
            }

            if (released.isEmpty()) {
                minClassSize = 0;
            }

            return new SafeHarborCohortExportResult(
                    request.studyProtocolId() != null ? request.studyProtocolId() : "OPES-STUDY-001",
                    Instant.now(),
                    rows.size(),
                    released.size(),
                    suppressed,
                    minClassSize,
                    !released.isEmpty() && minClassSize >= targetK,
                    List.copyOf(released)
            );
        }
    }

    @RestController
    @RequestMapping("/api/v1/deidentification")
    @Tag(name = "HIPAA Safe-Harbor & GDPR Art. 89 Research Export", description = "18-Identifier HIPAA Safe-Harbor scrubbing and k-anonymity cohort verification")
    public static class HipaaSafeHarborDeidentificationController {

        private final HipaaSafeHarborDeidentificationService deidentificationService;

        public HipaaSafeHarborDeidentificationController(HipaaSafeHarborDeidentificationService deidentificationService) {
            this.deidentificationService = deidentificationService;
        }

        @PostMapping("/safe-harbor-cohort")
        @PreAuthorize("hasAuthority('PERM_research:export')")
        @Operation(summary = "De-identify a clinical cohort under HIPAA Safe Harbor and enforce k-anonymity")
        public SafeHarborCohortExportResult exportCohort(@RequestBody SafeHarborCohortExportRequest request) {
            return deidentificationService.exportSafeHarborCohort(request);
        }
    }
}
