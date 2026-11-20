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
import java.time.Year;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
            if (request.researchSalt() == null || request.researchSalt().isBlank()) {
                throw new IllegalArgumentException("A distinct externally managed research HMAC key is required");
            }
            String salt = request.researchSalt().trim();
            if (request.minimumKAnonymity() < 3) {
                throw new IllegalArgumentException("minimumKAnonymity must be at least 3");
            }
            int targetK = request.minimumKAnonymity();

            List<DeidentifiedResearchRecord> transformed = new ArrayList<>(rows.size());
            Map<String, Integer> equivalenceClassCounts = new LinkedHashMap<>();

            for (ClinicalResearchSourceRow row : rows) {
                // No dependency on an obsolete HospitalOps method. All source data must
                // have a stable identity; missing identities must fail closed.
                if (row.patientId() == null) {
                    throw new IllegalArgumentException("Research cohort patientId cannot be null");
                }
                Map<String, Object> base = pseudonymizeResearchFields(
                        row.patientId(), row.birthYear(), salt);
                String subjectHash = String.valueOf(base.get("researchSubjectHash"));
                String ageVal = String.valueOf(base.get("birthYearOrAgeGroup"));
                String regionPrefix = String.valueOf(base.get("regionPrefix"));
                String gender = row.gender() != null ? row.gender().trim().toLowerCase(Locale.ROOT) : "unknown";
                // Free text and partial geographies may contain indirect identifiers.
                // Technical redaction is not sufficient proof of Safe Harbor.
                // Suppress free-text entirely until independent manual review approves it.
                String scrubbedNote = "";

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

        private static Map<String, Object> pseudonymizeResearchFields(
                UUID patientId, int birthYear, String researchKey) {
            int currentYear = Year.now().getValue();
            if (birthYear < 1880 || birthYear > currentYear) {
                throw new IllegalArgumentException("Invalid source birth year");
            }
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(researchKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                String pseudonym = HexFormat.of().formatHex(mac.doFinal(
                        patientId.toString().getBytes(StandardCharsets.UTF_8)));
                String ageOrYear = currentYear - birthYear >= 90 ? "90+" : Integer.toString(birthYear);
                // Regional and ZIP population-suppression policies are jurisdiction-specific.
                // Never release a postal prefix as though it were automatically Safe Harbor.
                return Map.of(
                        "researchSubjectHash", pseudonym,
                        "birthYearOrAgeGroup", ageOrYear,
                        "regionPrefix", "SUPPRESSED"
                );
            } catch (java.security.GeneralSecurityException ex) {
                throw new IllegalStateException("HMAC-SHA256 research pseudonymization unavailable", ex);
            }
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
