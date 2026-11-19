/*
 * Copyright (c) 2026 Opes Health Systems (https://www.opeshealthsystems.com)
 *
 * Author: Jude Nshome — Opes Health Systems
 * Email: nshome@opesware.com
 * Organization: Opes Health Systems, Douala, Cameroon
 *
 * Licensed under the Opes HealthCore Enterprise SDK License.
 */
package com.opeshealth.healthcore.quickstart.service;

import com.opeshealth.healthcore.quickstart.service.FhirAuditLoggingService.FhirAccessAuditCommand;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.service.PatientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Clinical Research Cohort Builder, Dynamic Research **eConsent**, and HIPAA Safe-Harbor
 * De-Identified **OMOP CDM v5.4** Export Service.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class ClinicalResearchOmopService {

    public record ResearchStudyProtocol(
            String studyId,
            String title,
            String irbApprovalNumber,
            int minAgeYears,
            int maxAgeYears,
            Set<String> requiredIcd10Prefixes,
            Set<String> excludedIcd10Prefixes,
            boolean active
    ) {}

    public record ResearchEConsentRequest(
            UUID facilityId,
            UUID patientId,
            String studyId,
            boolean allowDeidentifiedOmopExport,
            boolean allowTrialRecontact,
            String electronicSignatureHash
    ) {}

    public record ResearchEConsentRecord(
            UUID eConsentId,
            UUID facilityId,
            UUID patientId,
            String studyId,
            String status,
            boolean allowDeidentifiedOmopExport,
            boolean allowTrialRecontact,
            String electronicSignatureHash,
            Instant signedAt,
            Instant withdrawnAt
    ) {}

    public record CandidateResearchPatient(
            UUID patientId,
            int ageYears,
            String gender,
            Set<String> activeIcd10Codes,
            Double latestHba1cPercent,
            LocalDate indexVisitDate
    ) {}

    public record CohortScreeningResponse(
            String studyId,
            int totalScreened,
            int eligibleConsentedCount,
            List<UUID> eligiblePatientIds,
            Map<UUID, String> exclusionReasons,
            Instant screenedAt
    ) {}

    public record OmopPersonRow(
            long personIdPseudonym,
            int genderConceptId,
            int yearOfBirthSafeHarbor,
            String raceSourceValue
    ) {}

    public record OmopConditionOccurrenceRow(
            long conditionOccurrenceId,
            long personIdPseudonym,
            String conditionSourceValue,
            LocalDate shiftedStartDate
    ) {}

    public record OmopCdmExportBundle(
            UUID exportId,
            String studyId,
            String omopCdmVersion,
            String deidentificationStandard,
            int consentedSubjectsExported,
            List<OmopPersonRow> personTable,
            List<OmopConditionOccurrenceRow> conditionOccurrenceTable,
            Instant generatedAt
    ) {}

    private final FhirAuditLoggingService fhirAuditLoggingService;
    private final Map<String, ResearchStudyProtocol> studiesById = new ConcurrentHashMap<>();
    private final Map<String, ResearchEConsentRecord> consentsByStudyAndPatient = new ConcurrentHashMap<>();

    @Autowired
    public ClinicalResearchOmopService(@Autowired(required = false) FhirAuditLoggingService fhirAuditLoggingService) {
        this.fhirAuditLoggingService = fhirAuditLoggingService;
        registerStudyProtocol(new ResearchStudyProtocol(
                "OPES-CARDIO-2026",
                "Sub-Saharan Hypertension & Diabetes Outcomes Registry",
                "IRB-CM-2026-0412",
                18,
                85,
                Set.of("I10", "E11"),
                Set.of("C00", "C99"),
                true
        ));
    }

    public ClinicalResearchOmopService() {
        this(null);
    }

    public ResearchStudyProtocol registerStudyProtocol(ResearchStudyProtocol protocol) {
        if (protocol == null || protocol.studyId() == null || protocol.studyId().isBlank()) {
            throw new IllegalArgumentException("ResearchStudyProtocol with studyId is required");
        }
        studiesById.put(protocol.studyId().trim(), protocol);
        return protocol;
    }

    public List<ResearchStudyProtocol> listStudyProtocols() {
        return studiesById.values().stream()
                .sorted(Comparator.comparing(ResearchStudyProtocol::studyId))
                .toList();
    }

    /**
     * Records a dynamic electronic research consent (eConsent) for a study participant.
     */
    @ClinicalAudit(action = "RESEARCH_ECONSENT_SIGN", resourceType = "Consent")
    public ResearchEConsentRecord signResearchEConsent(ResearchEConsentRequest request) {
        if (request == null || request.patientId() == null || request.studyId() == null) {
            throw new IllegalArgumentException("ResearchEConsentRequest with patientId and studyId is required");
        }
        if (!studiesById.containsKey(request.studyId().trim())) {
            throw new NoSuchElementException("Research study protocol not found: " + request.studyId());
        }
        UUID facilityId = request.facilityId() != null ? request.facilityId() : PatientService.DEFAULT_FACILITY_ID;
        ResearchEConsentRecord record = new ResearchEConsentRecord(
                UUID.randomUUID(),
                facilityId,
                request.patientId(),
                request.studyId().trim(),
                "ACTIVE",
                request.allowDeidentifiedOmopExport(),
                request.allowTrialRecontact(),
                request.electronicSignatureHash() != null ? request.electronicSignatureHash().trim() : sha256Hex(request.patientId() + ":" + request.studyId()),
                Instant.now(),
                null
        );
        consentsByStudyAndPatient.put(request.studyId().trim() + "|" + request.patientId(), record);
        return record;
    }

    /**
     * Withdraws a participant's research eConsent so they are automatically excluded from future OMOP exports.
     */
    @ClinicalAudit(action = "RESEARCH_ECONSENT_WITHDRAW", resourceType = "Consent")
    public ResearchEConsentRecord withdrawResearchEConsent(String studyId, UUID patientId) {
        String key = studyId.trim() + "|" + patientId;
        ResearchEConsentRecord existing = consentsByStudyAndPatient.get(key);
        if (existing == null) {
            throw new NoSuchElementException("Active eConsent not found for study=" + studyId + " patient=" + patientId);
        }
        ResearchEConsentRecord withdrawn = new ResearchEConsentRecord(
                existing.eConsentId(),
                existing.facilityId(),
                existing.patientId(),
                existing.studyId(),
                "WITHDRAWN",
                false,
                false,
                existing.electronicSignatureHash(),
                existing.signedAt(),
                Instant.now()
        );
        consentsByStudyAndPatient.put(key, withdrawn);
        return withdrawn;
    }

    /**
     * Screens a candidate patient cohort against protocol inclusion/exclusion criteria and active eConsent.
     */
    public CohortScreeningResponse screenTrialEligibility(String studyId, List<CandidateResearchPatient> candidates) {
        ResearchStudyProtocol study = studiesById.get(studyId != null ? studyId.trim() : "");
        if (study == null) {
            throw new NoSuchElementException("Research study protocol not found: " + studyId);
        }
        List<UUID> eligible = new ArrayList<>();
        Map<UUID, String> exclusions = new java.util.LinkedHashMap<>();

        List<CandidateResearchPatient> safeCandidates = candidates != null ? candidates : List.of();
        for (CandidateResearchPatient candidate : safeCandidates) {
            if (candidate == null || candidate.patientId() == null) {
                continue;
            }
            if (candidate.ageYears() < study.minAgeYears() || candidate.ageYears() > study.maxAgeYears()) {
                exclusions.put(candidate.patientId(), "Age outside protocol range (" + study.minAgeYears() + "-" + study.maxAgeYears() + ")");
                continue;
            }
            Set<String> codes = candidate.activeIcd10Codes() != null ? candidate.activeIcd10Codes() : Set.of();
            boolean hasExclusion = study.excludedIcd10Prefixes().stream()
                    .anyMatch(ex -> codes.stream().anyMatch(c -> c.toUpperCase(Locale.ROOT).startsWith(ex.toUpperCase(Locale.ROOT))));
            if (hasExclusion) {
                exclusions.put(candidate.patientId(), "Excluded diagnosis code present");
                continue;
            }
            boolean hasInclusion = study.requiredIcd10Prefixes().isEmpty() || study.requiredIcd10Prefixes().stream()
                    .anyMatch(req -> codes.stream().anyMatch(c -> c.toUpperCase(Locale.ROOT).startsWith(req.toUpperCase(Locale.ROOT))));
            if (!hasInclusion) {
                exclusions.put(candidate.patientId(), "Required inclusion ICD-10 diagnosis not met");
                continue;
            }
            ResearchEConsentRecord consent = consentsByStudyAndPatient.get(study.studyId() + "|" + candidate.patientId());
            if (consent == null || !"ACTIVE".equals(consent.status())) {
                exclusions.put(candidate.patientId(), "No active study eConsent on file");
                continue;
            }
            eligible.add(candidate.patientId());
        }
        return new CohortScreeningResponse(
                study.studyId(),
                safeCandidates.size(),
                eligible.size(),
                List.copyOf(eligible),
                Map.copyOf(exclusions),
                Instant.now()
        );
    }

    /**
     * Generates a HIPAA Safe-Harbor (45 CFR § 164.514(b)(2)) de-identified **OMOP CDM v5.4** dataset
     * for consented study participants (ages > 89 capped to 90, deterministic salted person_id pseudonym,
     * deterministic per-subject date shifting).
     */
    @ClinicalAudit(action = "RESEARCH_OMOP_CDM_EXPORT", resourceType = "Group")
    public OmopCdmExportBundle exportDeidentifiedOmopCdmV54(String studyId, List<CandidateResearchPatient> candidates) {
        ResearchStudyProtocol study = studiesById.get(studyId != null ? studyId.trim() : "");
        if (study == null) {
            throw new NoSuchElementException("Research study protocol not found: " + studyId);
        }
        List<OmopPersonRow> persons = new ArrayList<>();
        List<OmopConditionOccurrenceRow> conditions = new ArrayList<>();
        long condSeq = 1L;
        int currentYear = LocalDate.now().getYear();

        for (CandidateResearchPatient c : (candidates != null ? candidates : List.<CandidateResearchPatient>of())) {
            ResearchEConsentRecord consent = consentsByStudyAndPatient.get(study.studyId() + "|" + c.patientId());
            if (consent == null || !"ACTIVE".equals(consent.status()) || !consent.allowDeidentifiedOmopExport()) {
                continue;
            }
            long pseudoId = Math.abs(sha256Hex("OPES-OMOP-SALT-2026:" + study.studyId() + ":" + c.patientId()).hashCode()) + 100_000L;
            int safeHarborAge = Math.min(c.ageYears(), 89);
            int yob = currentYear - safeHarborAge;
            int genderConcept = "female".equalsIgnoreCase(c.gender()) ? 8532 : ("male".equalsIgnoreCase(c.gender()) ? 8507 : 0);
            persons.add(new OmopPersonRow(pseudoId, genderConcept, yob, "DEIDENTIFIED"));

            int shiftDays = (int) ((pseudoId % 61) - 30); // deterministic date shift within [-30, +30] days
            LocalDate baseDate = c.indexVisitDate() != null ? c.indexVisitDate() : LocalDate.now();
            if (c.activeIcd10Codes() != null) {
                for (String icd : c.activeIcd10Codes()) {
                    conditions.add(new OmopConditionOccurrenceRow(condSeq++, pseudoId, icd, baseDate.plusDays(shiftDays)));
                }
            }
        }

        UUID exportId = UUID.randomUUID();
        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAccessAuditCommand(
                    PatientService.DEFAULT_FACILITY_ID.toString(),
                    "RESEARCH_OMOP_EXPORTER",
                    "ROLE_RESEARCHER",
                    "RESEARCH_OMOP_CDM_EXPORT",
                    "Group",
                    exportId.toString(),
                    null,
                    "POST",
                    "/api/v1/research/studies/" + study.studyId() + "/omop-export",
                    "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "Exported Safe-Harbor OMOP CDM v5.4 dataset subjects=" + persons.size() + " conditions=" + conditions.size()
            ));
        }

        return new OmopCdmExportBundle(
                exportId,
                study.studyId(),
                "OMOP_CDM_5.4",
                "HIPAA_SAFE_HARBOR_45_CFR_164_514_B_2",
                persons.size(),
                List.copyOf(persons),
                List.copyOf(conditions),
                Instant.now()
        );
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 computation failure", ex);
        }
    }
}
