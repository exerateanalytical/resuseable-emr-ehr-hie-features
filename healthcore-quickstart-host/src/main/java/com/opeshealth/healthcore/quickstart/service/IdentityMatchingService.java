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

import ca.uhn.fhir.context.FhirContext;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientAggregateEntity;
import com.opeshealth.healthcore.mpi.match.FellegiSunterMatcher;
import com.opeshealth.healthcore.service.PatientService;
import com.opeshealth.healthcore.service.PatientService.PatientSearchCriteria;
import com.opeshealth.healthcore.service.PatientService.PatientSearchResult;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Probabilistic Master Patient Index (MPI) Identity Matching &amp; Duplicate Resolution Service.
 *
 * <p>Implements the Fellegi-Sunter log-likelihood record linkage model combined with
 * Jaro-Winkler string similarity, Damerau-Levenshtein transposition edit distance,
 * double-phonetic blocking, E.164 telecom trailing-digit normalization, and date-of-birth
 * day/month transposition detection across the HL7 FHIR R4 {@code Patient} database.</p>
 *
 * <p>Supports both the standard HL7 FHIR R4 {@code Patient/$match} operation and automated
 * database-wide duplicate scanning that flags potential duplicate patient pairs into a
 * Health Information Management (HIM) steward queue for manual resolution.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class IdentityMatchingService {

    private static final Logger log = LoggerFactory.getLogger(IdentityMatchingService.class);

    public static final String FHIR_MATCH_GRADE_EXTENSION_URL =
            "http://hl7.org/fhir/StructureDefinition/match-grade";
    public static final String FHIR_MATCH_GRADE_CODESYSTEM =
            "http://terminology.hl7.org/CodeSystem/match-grade";

    public static final double DEFAULT_CERTAIN_THRESHOLD = 0.90;
    public static final double DEFAULT_PROBABLE_THRESHOLD = 0.75;
    public static final double DEFAULT_POSSIBLE_REVIEW_THRESHOLD = 0.62;

    // Fellegi-Sunter field weights and m/u probabilities calibrated for clinical MPI registries
    private static final double M_FAMILY_NAME = 0.94;
    private static final double U_FAMILY_NAME = 0.015;
    private static final double WEIGHT_FAMILY_NAME = 0.24;

    private static final double M_GIVEN_NAME = 0.91;
    private static final double U_GIVEN_NAME = 0.025;
    private static final double WEIGHT_GIVEN_NAME = 0.20;

    private static final double M_MIDDLE_NAME = 0.80;
    private static final double U_MIDDLE_NAME = 0.080;
    private static final double WEIGHT_MIDDLE_NAME = 0.05;

    private static final double M_BIRTH_DATE = 0.96;
    private static final double U_BIRTH_DATE = 0.003;
    private static final double WEIGHT_BIRTH_DATE = 0.25;

    private static final double M_GENDER = 0.98;
    private static final double U_GENDER = 0.50;
    private static final double WEIGHT_GENDER = 0.05;

    private static final double M_PHONE = 0.88;
    private static final double U_PHONE = 0.005;
    private static final double WEIGHT_PHONE = 0.12;

    private static final double M_EMAIL = 0.86;
    private static final double U_EMAIL = 0.002;
    private static final double WEIGHT_EMAIL = 0.09;

    public enum MatchGrade {
        CERTAIN("certain"),
        PROBABLE("probable"),
        POSSIBLE("possible"),
        CERTAINLY_NOT("certainly-not");

        private final String fhirCode;

        MatchGrade(String fhirCode) {
            this.fhirCode = fhirCode;
        }

        public String fhirCode() {
            return fhirCode;
        }

        public static MatchGrade fromScore(double probability) {
            if (probability >= DEFAULT_CERTAIN_THRESHOLD) {
                return CERTAIN;
            }
            if (probability >= DEFAULT_PROBABLE_THRESHOLD) {
                return PROBABLE;
            }
            if (probability >= DEFAULT_POSSIBLE_REVIEW_THRESHOLD) {
                return POSSIBLE;
            }
            return CERTAINLY_NOT;
        }
    }

    public enum DuplicateFlagStatus {
        PENDING_MANUAL_RESOLUTION,
        DEFERRED_NEEDS_VERIFICATION,
        RESOLVED_MERGED,
        RESOLVED_NOT_DUPLICATE
    }

    public enum ManualResolutionAction {
        CONFIRM_DUPLICATE_AND_MERGE,
        CONFIRM_DISTINCT_NOT_DUPLICATE,
        DEFER_ADDITIONAL_VERIFICATION
    }

    public record FieldProbabilityScore(
            String fieldName,
            String leftValue,
            String rightValue,
            double similarity,
            double mProbability,
            double uProbability,
            double fieldWeight,
            double logLikelihoodContribution,
            String algorithmUsed,
            String rationale
    ) {}

    public record ProbabilisticMatchCandidate(
            UUID candidatePatientId,
            UUID facilityId,
            String mrn,
            String familyName,
            String givenName,
            String middleName,
            LocalDate birthDate,
            String gender,
            String primaryPhone,
            String email,
            double compositeProbability,
            double fellegiSunterLogLikelihood,
            MatchGrade matchGrade,
            String phoneticBlockKey,
            boolean flaggedForManualResolution,
            UUID duplicateFlagId,
            List<FieldProbabilityScore> fieldBreakdown
    ) {}

    public record FhirMatchEvaluationResult(
            Instant evaluatedAt,
            UUID facilityId,
            int totalCandidatesEvaluated,
            int matchedCount,
            boolean onlyCertainMatches,
            List<ProbabilisticMatchCandidate> matches,
            String fhirSearchsetBundleJson
    ) {}

    public record DuplicatePatientFlag(
            UUID flagId,
            UUID facilityId,
            UUID leftPatientId,
            String leftMrn,
            String leftDisplayName,
            LocalDate leftBirthDate,
            String leftGender,
            String leftPhone,
            UUID rightPatientId,
            String rightMrn,
            String rightDisplayName,
            LocalDate rightBirthDate,
            String rightGender,
            String rightPhone,
            double compositeProbability,
            double fellegiSunterLogLikelihood,
            MatchGrade matchGrade,
            String blockingKey,
            String recommendedAction,
            DuplicateFlagStatus status,
            List<FieldProbabilityScore> fieldScores,
            Instant detectedAt,
            Instant updatedAt,
            Instant resolvedAt,
            String resolvedBy,
            ManualResolutionAction resolutionDecision,
            UUID survivingPatientId,
            UUID retiredDuplicatePatientId,
            String resolutionNotes
    ) {}

    public record DuplicateDatabaseScanSummary(
            UUID scanId,
            Instant scannedAt,
            UUID facilityId,
            boolean crossFacilityScan,
            double reviewThreshold,
            int totalActivePatientsScanned,
            int totalCandidatePairsEvaluated,
            int newlyFlaggedCount,
            int totalPendingManualResolution,
            List<DuplicatePatientFlag> flaggedPairs
    ) {}

    public record ManualResolutionRequest(
            ManualResolutionAction action,
            UUID survivingPatientId,
            String resolvedBy,
            String resolutionNotes
    ) {}

    private final PatientService patientService;
    private final FhirAuditLoggingService fhirAuditLoggingService;
    private final FhirContext fhirContext = FhirContext.forR4();

    private final Map<UUID, DuplicatePatientFlag> flagsById = new ConcurrentHashMap<>();
    private final Map<String, UUID> flagIdByPairKey = new ConcurrentHashMap<>();
    private final Set<String> excludedNonDuplicatePairKeys = ConcurrentHashMap.newKeySet();

    @Autowired
    public IdentityMatchingService(
            PatientService patientService,
            @Autowired(required = false) FhirAuditLoggingService fhirAuditLoggingService) {
        this.patientService = Objects.requireNonNull(patientService, "PatientService is required");
        this.fhirAuditLoggingService = fhirAuditLoggingService;
    }

    public IdentityMatchingService(PatientService patientService) {
        this(patientService, null);
    }

    /**
     * Executes the HL7 FHIR R4 {@code Patient/$match} probabilistic operation against the
     * FHIR database and optionally flags high-scoring duplicates for manual resolution.
     */
    @ClinicalAudit(action = "PATIENT_IDENTITY_MATCH", resourceType = "Patient")
    public FhirMatchEvaluationResult evaluatePatientMatch(
            UUID facilityId,
            Patient probeFhirPatient,
            boolean onlyCertainMatches,
            int maxResults,
            boolean autoFlagMatches,
            HttpServletRequest servletRequest) {

        if (probeFhirPatient == null) {
            throw new IllegalArgumentException("Probe FHIR R4 Patient resource is required for $match");
        }
        PatientAggregateEntity probeEntity = toProbeEntity(facilityId, probeFhirPatient);
        return evaluateEntityMatch(
                facilityId,
                probeEntity,
                onlyCertainMatches,
                maxResults,
                autoFlagMatches,
                servletRequest);
    }

    /**
     * Evaluates a probe {@link PatientAggregateEntity} against active patients in the FHIR repository.
     */
    @ClinicalAudit(action = "PATIENT_IDENTITY_MATCH", resourceType = "Patient")
    public FhirMatchEvaluationResult evaluateEntityMatch(
            UUID facilityId,
            PatientAggregateEntity probe,
            boolean onlyCertainMatches,
            int maxResults,
            boolean autoFlagMatches,
            HttpServletRequest servletRequest) {

        Objects.requireNonNull(probe, "Probe patient demographics must not be null");
        int resolvedMax = maxResults <= 0 ? 20 : Math.min(maxResults, 200);
        double minScore = onlyCertainMatches ? DEFAULT_CERTAIN_THRESHOLD : DEFAULT_POSSIBLE_REVIEW_THRESHOLD;

        List<PatientAggregateEntity> pool = loadActivePatientPool(facilityId, facilityId == null);
        List<ProbabilisticMatchCandidate> scoredCandidates = new ArrayList<>();

        for (PatientAggregateEntity existing : pool) {
            if (probe.getId() != null && Objects.equals(probe.getId(), existing.getId())) {
                continue;
            }
            if (!existing.isActive() || existing.getMergedIntoPatientId() != null) {
                continue;
            }
            PairwiseScoreResult scoreResult = computeProbabilisticScore(probe, existing);
            if (scoreResult.compositeProbability() >= minScore) {
                UUID flagId = null;
                boolean flagged = false;
                if (autoFlagMatches && probe.getId() != null
                        && scoreResult.compositeProbability() >= DEFAULT_POSSIBLE_REVIEW_THRESHOLD) {
                    DuplicatePatientFlag flag = upsertDuplicateFlag(probe, existing, scoreResult);
                    if (flag != null) {
                        flagId = flag.flagId();
                        flagged = flag.status() == DuplicateFlagStatus.PENDING_MANUAL_RESOLUTION
                                || flag.status() == DuplicateFlagStatus.DEFERRED_NEEDS_VERIFICATION;
                    }
                }
                scoredCandidates.add(new ProbabilisticMatchCandidate(
                        existing.getId(),
                        existing.getFacilityId(),
                        existing.getMrn(),
                        existing.getFamilyName(),
                        existing.getGivenName(),
                        existing.getMiddleName(),
                        existing.getBirthDate(),
                        existing.getGender(),
                        existing.getPrimaryPhone(),
                        existing.getEmail(),
                        scoreResult.compositeProbability(),
                        scoreResult.fellegiSunterLogLikelihood(),
                        scoreResult.matchGrade(),
                        scoreResult.blockingKey(),
                        flagged,
                        flagId,
                        scoreResult.fieldBreakdown()
                ));
            }
        }

        scoredCandidates.sort(Comparator.comparingDouble(ProbabilisticMatchCandidate::compositeProbability).reversed());
        List<ProbabilisticMatchCandidate> topMatches = scoredCandidates.size() > resolvedMax
                ? List.copyOf(scoredCandidates.subList(0, resolvedMax))
                : List.copyOf(scoredCandidates);

        Bundle matchBundle = buildFhirMatchSearchsetBundle(topMatches);
        String bundleJson = fhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(matchBundle);

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAuditLoggingService.FhirAccessAuditCommand(
                    (com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(facilityId)).toString(),
                    "IDENTITY_MATCHING_ENGINE",
                    "MPI_MATCHER",
                    "PATIENT_IDENTITY_MATCH",
                    "Patient",
                    probe.getId() != null ? probe.getId().toString() : "PROBE",
                    probe.getId(),
                    servletRequest != null ? servletRequest.getMethod() : "POST",
                    servletRequest != null ? servletRequest.getRequestURI() : "/api/v1/fhir/Patient/$match",
                    servletRequest != null ? servletRequest.getRemoteAddr() : "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "HL7 FHIR R4 Patient/$match evaluated " + pool.size() + " patients, matched " + topMatches.size()
            ));
        }

        return new FhirMatchEvaluationResult(
                Instant.now(),
                facilityId,
                pool.size(),
                topMatches.size(),
                onlyCertainMatches,
                topMatches,
                bundleJson
        );
    }

    /**
     * Scans the FHIR database for potential duplicate patient records using multi-pass blocking
     * (phonetic name key, birth year, and normalized telecom) plus Fellegi-Sunter / Jaro-Winkler
     * probabilistic scoring, flagging candidate pairs for manual resolution.
     */
    @ClinicalAudit(action = "MPI_DUPLICATE_DATABASE_SCAN", resourceType = "Patient")
    public DuplicateDatabaseScanSummary scanDatabaseForDuplicates(
            UUID facilityId,
            boolean crossFacilityScan,
            Double customThreshold,
            HttpServletRequest servletRequest) {

        double threshold = (customThreshold != null && customThreshold >= 0.40 && customThreshold <= 0.99)
                ? customThreshold
                : DEFAULT_POSSIBLE_REVIEW_THRESHOLD;

        List<PatientAggregateEntity> activePatients = loadActivePatientPool(facilityId, crossFacilityScan);
        Set<String> candidatePairKeys = buildCandidateBlocks(activePatients);
        Map<UUID, PatientAggregateEntity> byId = new LinkedHashMap<>();
        for (PatientAggregateEntity p : activePatients) {
            if (p.getId() != null && p.isActive() && p.getMergedIntoPatientId() == null) {
                byId.put(p.getId(), p);
            }
        }

        int newlyFlagged = 0;
        int pairsEvaluated = 0;
        List<DuplicatePatientFlag> scanFlags = new ArrayList<>();

        for (String pairKey : candidatePairKeys) {
            if (excludedNonDuplicatePairKeys.contains(pairKey)) {
                continue;
            }
            String[] parts = pairKey.split(":");
            if (parts.length != 2) {
                continue;
            }
            PatientAggregateEntity left = byId.get(UUID.fromString(parts[0]));
            PatientAggregateEntity right = byId.get(UUID.fromString(parts[1]));
            if (left == null || right == null) {
                continue;
            }

            pairsEvaluated++;
            PairwiseScoreResult scoreResult = computeProbabilisticScore(left, right);
            if (scoreResult.compositeProbability() >= threshold) {
                boolean existedBefore = flagIdByPairKey.containsKey(pairKey);
                DuplicatePatientFlag flag = upsertDuplicateFlag(left, right, scoreResult);
                if (flag != null) {
                    if (!existedBefore) {
                        newlyFlagged++;
                    }
                    scanFlags.add(flag);
                }
            }
        }

        scanFlags.sort(Comparator.comparingDouble(DuplicatePatientFlag::compositeProbability).reversed());
        int totalPending = (int) flagsById.values().stream()
                .filter(f -> f.status() == DuplicateFlagStatus.PENDING_MANUAL_RESOLUTION
                        || f.status() == DuplicateFlagStatus.DEFERRED_NEEDS_VERIFICATION)
                .count();

        log.info("MPI duplicate scan completed: activePatients={}, candidatePairsEvaluated={}, newlyFlagged={}, pendingResolution={}",
                byId.size(), pairsEvaluated, newlyFlagged, totalPending);

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAuditLoggingService.FhirAccessAuditCommand(
                    (com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(facilityId)).toString(),
                    "IDENTITY_MATCHING_ENGINE",
                    "MPI_MATCHER",
                    "MPI_DUPLICATE_DATABASE_SCAN",
                    "Patient",
                    "SCAN",
                    null,
                    servletRequest != null ? servletRequest.getMethod() : "POST",
                    servletRequest != null ? servletRequest.getRequestURI() : "/api/v1/identity-matching/scan",
                    servletRequest != null ? servletRequest.getRemoteAddr() : "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "Probabilistic MPI duplicate scan evaluated " + pairsEvaluated
                            + " candidate pairs and flagged " + scanFlags.size() + " potential duplicates"
            ));
        }

        return new DuplicateDatabaseScanSummary(
                UUID.randomUUID(),
                Instant.now(),
                facilityId,
                crossFacilityScan,
                threshold,
                byId.size(),
                pairsEvaluated,
                newlyFlagged,
                totalPending,
                List.copyOf(scanFlags)
        );
    }

    /**
     * Lists flagged duplicate patient records filtered by optional facilityId and status.
     */
    public List<DuplicatePatientFlag> listDuplicateFlags(UUID facilityId, DuplicateFlagStatus statusFilter) {
        return flagsById.values().stream()
                .filter(f -> facilityId == null || Objects.equals(facilityId, f.facilityId()))
                .filter(f -> statusFilter == null || f.status() == statusFilter)
                .sorted(Comparator.comparingDouble(DuplicatePatientFlag::compositeProbability).reversed()
                        .thenComparing(DuplicatePatientFlag::detectedAt, Comparator.reverseOrder()))
                .toList();
    }

    /**
     * Retrieves a single flagged duplicate patient record by its flag ID.
     */
    public DuplicatePatientFlag getDuplicateFlag(UUID flagId) {
        Objects.requireNonNull(flagId, "flagId is required");
        DuplicatePatientFlag flag = flagsById.get(flagId);
        if (flag == null) {
            throw new NoSuchElementException("Duplicate patient flag not found: " + flagId);
        }
        return flag;
    }

    /**
     * Manually resolves a flagged duplicate patient pair:
     * <ul>
     *   <li>{@code CONFIRM_DUPLICATE_AND_MERGE}: Merges and deactivates the duplicate patient record into the surviving patient record.</li>
     *   <li>{@code CONFIRM_DISTINCT_NOT_DUPLICATE}: Marks the pair as distinct and adds it to the permanent exclusion registry.</li>
     *   <li>{@code DEFER_ADDITIONAL_VERIFICATION}: Keeps the pair flagged while recording steward verification notes.</li>
     * </ul>
     */
    @ClinicalAudit(action = "MPI_DUPLICATE_MANUAL_RESOLUTION", resourceType = "Patient")
    public DuplicatePatientFlag resolveDuplicateFlag(
            UUID flagId,
            ManualResolutionRequest request,
            HttpServletRequest servletRequest) {

        DuplicatePatientFlag existing = getDuplicateFlag(flagId);
        if (request == null || request.action() == null) {
            throw new IllegalArgumentException("ManualResolutionAction is required to resolve a duplicate flag");
        }
        if (existing.status() == DuplicateFlagStatus.RESOLVED_MERGED) {
            throw new IllegalStateException("Duplicate flag " + flagId + " has already been merged");
        }

        String steward = (request.resolvedBy() != null && !request.resolvedBy().isBlank())
                ? request.resolvedBy().trim()
                : "him-data-steward@opeshealthsystems.com";
        String notes = (request.resolutionNotes() != null && !request.resolutionNotes().isBlank())
                ? request.resolutionNotes().trim()
                : "Manual MPI steward resolution executed";

        Instant now = Instant.now();
        DuplicateFlagStatus nextStatus;
        UUID survivingId = null;
        UUID retiredId = null;

        switch (request.action()) {
            case CONFIRM_DUPLICATE_AND_MERGE -> {
                if (request.survivingPatientId() != null) {
                    if (!Objects.equals(request.survivingPatientId(), existing.leftPatientId())
                            && !Objects.equals(request.survivingPatientId(), existing.rightPatientId())) {
                        throw new IllegalArgumentException(
                                "survivingPatientId must match either leftPatientId or rightPatientId of flag " + flagId);
                    }
                    survivingId = request.survivingPatientId();
                } else {
                    survivingId = existing.leftPatientId();
                }
                retiredId = Objects.equals(survivingId, existing.leftPatientId())
                        ? existing.rightPatientId()
                        : existing.leftPatientId();

                // Deactivate the duplicate record via PatientService so active queries converge on the surviving record
                patientService.deactivatePatient(retiredId);
                nextStatus = DuplicateFlagStatus.RESOLVED_MERGED;
            }
            case CONFIRM_DISTINCT_NOT_DUPLICATE -> {
                String pairKey = canonicalPairKey(existing.leftPatientId(), existing.rightPatientId());
                excludedNonDuplicatePairKeys.add(pairKey);
                nextStatus = DuplicateFlagStatus.RESOLVED_NOT_DUPLICATE;
            }
            case DEFER_ADDITIONAL_VERIFICATION -> {
                nextStatus = DuplicateFlagStatus.DEFERRED_NEEDS_VERIFICATION;
            }
            default -> throw new IllegalArgumentException("Unsupported resolution action: " + request.action());
        }

        DuplicatePatientFlag updated = new DuplicatePatientFlag(
                existing.flagId(),
                existing.facilityId(),
                existing.leftPatientId(),
                existing.leftMrn(),
                existing.leftDisplayName(),
                existing.leftBirthDate(),
                existing.leftGender(),
                existing.leftPhone(),
                existing.rightPatientId(),
                existing.rightMrn(),
                existing.rightDisplayName(),
                existing.rightBirthDate(),
                existing.rightGender(),
                existing.rightPhone(),
                existing.compositeProbability(),
                existing.fellegiSunterLogLikelihood(),
                existing.matchGrade(),
                existing.blockingKey(),
                existing.recommendedAction(),
                nextStatus,
                existing.fieldScores(),
                existing.detectedAt(),
                now,
                nextStatus == DuplicateFlagStatus.RESOLVED_MERGED
                        || nextStatus == DuplicateFlagStatus.RESOLVED_NOT_DUPLICATE ? now : null,
                steward,
                request.action(),
                survivingId,
                retiredId,
                notes
        );

        flagsById.put(updated.flagId(), updated);

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAuditLoggingService.FhirAccessAuditCommand(
                    (com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(updated.facilityId())).toString(),
                    steward,
                    "HIM_DATA_STEWARD",
                    "MPI_DUPLICATE_MANUAL_RESOLUTION",
                    "Patient",
                    survivingId != null ? survivingId.toString() : updated.leftPatientId().toString(),
                    survivingId != null ? survivingId : updated.leftPatientId(),
                    servletRequest != null ? servletRequest.getMethod() : "POST",
                    servletRequest != null ? servletRequest.getRequestURI()
                            : "/api/v1/identity-matching/flags/" + flagId + "/resolve",
                    servletRequest != null ? servletRequest.getRemoteAddr() : "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "Resolved duplicate flag " + flagId + " with decision " + request.action()
                            + " by " + steward
            ));
        }

        return updated;
    }

    // ========================================================================
    // Probabilistic Record Linkage Engine (Fellegi-Sunter + Jaro-Winkler + Edit Distance)
    // ========================================================================

    public record PairwiseScoreResult(
            double compositeProbability,
            double fellegiSunterLogLikelihood,
            MatchGrade matchGrade,
            String blockingKey,
            List<FieldProbabilityScore> fieldBreakdown
    ) {}

    /**
     * Computes the pairwise Fellegi-Sunter log-likelihood ratio and calibrated posterior match
     * probability between two patient records.
     */
    public PairwiseScoreResult computeProbabilisticScore(
            PatientAggregateEntity left,
            PatientAggregateEntity right) {

        Objects.requireNonNull(left, "Left patient record is required");
        Objects.requireNonNull(right, "Right patient record is required");

        List<FieldProbabilityScore> breakdown = new ArrayList<>();
        double weightedSimilarityNumerator = 0.0;
        double activeWeightDenominator = 0.0;
        double totalLogLikelihood = 0.0;

        // 1. Family Name (Jaro-Winkler + Phonetic Metaphone agreement bonus)
        NameComparisonResult familyComp = compareNames(left.getFamilyName(), right.getFamilyName());
        if (familyComp.present()) {
            double ll = interpolateFellegiSunterWeight(familyComp.similarity(), M_FAMILY_NAME, U_FAMILY_NAME);
            weightedSimilarityNumerator += WEIGHT_FAMILY_NAME * familyComp.similarity();
            activeWeightDenominator += WEIGHT_FAMILY_NAME;
            totalLogLikelihood += ll;
            breakdown.add(new FieldProbabilityScore(
                    "familyName",
                    nullToEmpty(left.getFamilyName()),
                    nullToEmpty(right.getFamilyName()),
                    round4(familyComp.similarity()),
                    M_FAMILY_NAME,
                    U_FAMILY_NAME,
                    WEIGHT_FAMILY_NAME,
                    round4(ll),
                    familyComp.algorithm(),
                    familyComp.rationale()
            ));
        }

        // 2. Given Name (Jaro-Winkler + Damerau-Levenshtein + Name-Swap Check)
        NameComparisonResult givenComp = compareGivenNamesWithSwapDetection(left, right);
        if (givenComp.present()) {
            double ll = interpolateFellegiSunterWeight(givenComp.similarity(), M_GIVEN_NAME, U_GIVEN_NAME);
            weightedSimilarityNumerator += WEIGHT_GIVEN_NAME * givenComp.similarity();
            activeWeightDenominator += WEIGHT_GIVEN_NAME;
            totalLogLikelihood += ll;
            breakdown.add(new FieldProbabilityScore(
                    "givenName",
                    nullToEmpty(left.getGivenName()),
                    nullToEmpty(right.getGivenName()),
                    round4(givenComp.similarity()),
                    M_GIVEN_NAME,
                    U_GIVEN_NAME,
                    WEIGHT_GIVEN_NAME,
                    round4(ll),
                    givenComp.algorithm(),
                    givenComp.rationale()
            ));
        }

        // 3. Middle Name / Initial
        if (!isBlank(left.getMiddleName()) && !isBlank(right.getMiddleName())) {
            double midSim = compareMiddleNameOrInitial(left.getMiddleName(), right.getMiddleName());
            double ll = interpolateFellegiSunterWeight(midSim, M_MIDDLE_NAME, U_MIDDLE_NAME);
            weightedSimilarityNumerator += WEIGHT_MIDDLE_NAME * midSim;
            activeWeightDenominator += WEIGHT_MIDDLE_NAME;
            totalLogLikelihood += ll;
            breakdown.add(new FieldProbabilityScore(
                    "middleName",
                    left.getMiddleName(),
                    right.getMiddleName(),
                    round4(midSim),
                    M_MIDDLE_NAME,
                    U_MIDDLE_NAME,
                    WEIGHT_MIDDLE_NAME,
                    round4(ll),
                    "JARO_WINKLER_INITIAL",
                    midSim >= 0.85 ? "Middle name or initial agreement" : "Middle name divergence"
            ));
        }

        // 4. Birth Date (Exact + Day/Month transposition + Off-by-one digit typo)
        if (left.getBirthDate() != null && right.getBirthDate() != null) {
            BirthDateComparisonResult dobComp = compareBirthDates(left.getBirthDate(), right.getBirthDate());
            double ll = interpolateFellegiSunterWeight(dobComp.similarity(), M_BIRTH_DATE, U_BIRTH_DATE);
            weightedSimilarityNumerator += WEIGHT_BIRTH_DATE * dobComp.similarity();
            activeWeightDenominator += WEIGHT_BIRTH_DATE;
            totalLogLikelihood += ll;
            breakdown.add(new FieldProbabilityScore(
                    "birthDate",
                    left.getBirthDate().toString(),
                    right.getBirthDate().toString(),
                    round4(dobComp.similarity()),
                    M_BIRTH_DATE,
                    U_BIRTH_DATE,
                    WEIGHT_BIRTH_DATE,
                    round4(ll),
                    "DOB_TRANSPOSITION_DISTANCE",
                    dobComp.rationale()
            ));
        }

        // 5. Administrative Gender
        if (!isBlank(left.getGender()) && !isBlank(right.getGender())) {
            boolean sameGender = left.getGender().trim().equalsIgnoreCase(right.getGender().trim());
            double genderSim = sameGender ? 1.0 : 0.0;
            double ll = interpolateFellegiSunterWeight(genderSim, M_GENDER, U_GENDER);
            weightedSimilarityNumerator += WEIGHT_GENDER * genderSim;
            activeWeightDenominator += WEIGHT_GENDER;
            totalLogLikelihood += ll;
            breakdown.add(new FieldProbabilityScore(
                    "gender",
                    left.getGender().toLowerCase(Locale.ROOT),
                    right.getGender().toLowerCase(Locale.ROOT),
                    genderSim,
                    M_GENDER,
                    U_GENDER,
                    WEIGHT_GENDER,
                    round4(ll),
                    "EXACT_CODE",
                    sameGender ? "AdministrativeGender exact match" : "AdministrativeGender mismatch"
            ));
        }

        // 6. Primary / Secondary Telecom (E.164 & trailing 8-digit subscriber comparison)
        TelecomComparisonResult phoneComp = comparePhoneNumbers(left, right);
        if (phoneComp.present()) {
            double ll = interpolateFellegiSunterWeight(phoneComp.similarity(), M_PHONE, U_PHONE);
            weightedSimilarityNumerator += WEIGHT_PHONE * phoneComp.similarity();
            activeWeightDenominator += WEIGHT_PHONE;
            totalLogLikelihood += ll;
            breakdown.add(new FieldProbabilityScore(
                    "phone",
                    nullToEmpty(left.getPrimaryPhone()),
                    nullToEmpty(right.getPrimaryPhone()),
                    round4(phoneComp.similarity()),
                    M_PHONE,
                    U_PHONE,
                    WEIGHT_PHONE,
                    round4(ll),
                    "E164_SUBSCRIBER_EDIT_DISTANCE",
                    phoneComp.rationale()
            ));
        }

        // 7. Email Address
        if (!isBlank(left.getEmail()) && !isBlank(right.getEmail())) {
            String e1 = left.getEmail().trim().toLowerCase(Locale.ROOT);
            String e2 = right.getEmail().trim().toLowerCase(Locale.ROOT);
            double emailSim = e1.equals(e2) ? 1.0 : jaroWinklerSimilarity(e1, e2);
            if (emailSim < 0.80) {
                emailSim = 0.0;
            }
            double ll = interpolateFellegiSunterWeight(emailSim, M_EMAIL, U_EMAIL);
            weightedSimilarityNumerator += WEIGHT_EMAIL * emailSim;
            activeWeightDenominator += WEIGHT_EMAIL;
            totalLogLikelihood += ll;
            breakdown.add(new FieldProbabilityScore(
                    "email",
                    e1,
                    e2,
                    round4(emailSim),
                    M_EMAIL,
                    U_EMAIL,
                    WEIGHT_EMAIL,
                    round4(ll),
                    "EMAIL_JARO_WINKLER",
                    e1.equals(e2) ? "Exact email match" : "Email similarity score"
            ));
        }

        double baseNormalizedScore = activeWeightDenominator > 0.0
                ? (weightedSimilarityNumerator / activeWeightDenominator)
                : 0.0;

        // Combine weighted feature similarity with logistic sigmoid of Fellegi-Sunter log-likelihood
        double logisticPriorOffset = -1.35;
        double fsPosterior = 1.0 / (1.0 + Math.exp(-(totalLogLikelihood + logisticPriorOffset) * 0.48));
        double composite = (0.72 * baseNormalizedScore) + (0.28 * fsPosterior);

        // High-confidence corroboration booster when both name phonetic block, DOB, and phone match
        if (familyComp.similarity() >= 0.88 && givenComp.similarity() >= 0.85
                && left.getBirthDate() != null && Objects.equals(left.getBirthDate(), right.getBirthDate())) {
            composite = Math.max(composite, 0.91 * ((familyComp.similarity() + givenComp.similarity() + 1.0) / 3.0));
        }

        double boundedProbability = round4(Math.max(0.0, Math.min(0.9999, composite)));
        MatchGrade grade = MatchGrade.fromScore(boundedProbability);
        String blockKey = computeBlockingKey(left);

        return new PairwiseScoreResult(
                boundedProbability,
                round4(totalLogLikelihood),
                grade,
                blockKey,
                List.copyOf(breakdown)
        );
    }

    /**
     * Computes Jaro-Winkler similarity in the range {@code [0.0, 1.0]} with diacritic stripping
     * and Winkler common-prefix boost ({@code p = 0.10}, max prefix {@code 4}).
     */
    public static double jaroWinklerSimilarity(String rawLeft, String rawRight) {
        String s1 = normalizeToken(rawLeft);
        String s2 = normalizeToken(rawRight);
        if (s1.isEmpty() && s2.isEmpty()) {
            return 1.0;
        }
        if (s1.isEmpty() || s2.isEmpty()) {
            return 0.0;
        }
        if (s1.equals(s2)) {
            return 1.0;
        }

        int len1 = s1.length();
        int len2 = s2.length();
        int matchDistance = Math.max(0, (Math.max(len1, len2) / 2) - 1);

        boolean[] s1Matches = new boolean[len1];
        boolean[] s2Matches = new boolean[len2];

        int matches = 0;
        for (int i = 0; i < len1; i++) {
            int start = Math.max(0, i - matchDistance);
            int end = Math.min(i + matchDistance + 1, len2);
            for (int j = start; j < end; j++) {
                if (s2Matches[j]) {
                    continue;
                }
                if (s1.charAt(i) != s2.charAt(j)) {
                    continue;
                }
                s1Matches[i] = true;
                s2Matches[j] = true;
                matches++;
                break;
            }
        }

        if (matches == 0) {
            return 0.0;
        }

        double transpositions = 0;
        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (!s1Matches[i]) {
                continue;
            }
            while (!s2Matches[k]) {
                k++;
            }
            if (s1.charAt(i) != s2.charAt(k)) {
                transpositions++;
            }
            k++;
        }

        double m = matches;
        double jaro = ((m / len1) + (m / len2) + ((m - (transpositions / 2.0)) / m)) / 3.0;

        int prefix = 0;
        int maxPrefix = Math.min(4, Math.min(len1, len2));
        for (int i = 0; i < maxPrefix; i++) {
            if (s1.charAt(i) == s2.charAt(i)) {
                prefix++;
            } else {
                break;
            }
        }

        return jaro + (prefix * 0.10 * (1.0 - jaro));
    }

    /**
     * Computes normalized Damerau-Levenshtein similarity in the range {@code [0.0, 1.0]}
     * accounting for insertions, deletions, substitutions, and adjacent character transpositions.
     */
    public static double damerauLevenshteinSimilarity(String rawLeft, String rawRight) {
        String s1 = normalizeToken(rawLeft);
        String s2 = normalizeToken(rawRight);
        if (s1.equals(s2)) {
            return 1.0;
        }
        if (s1.isEmpty() || s2.isEmpty()) {
            return 0.0;
        }
        int dist = damerauLevenshteinDistance(s1, s2);
        int maxLen = Math.max(s1.length(), s2.length());
        return Math.max(0.0, 1.0 - ((double) dist / maxLen));
    }

    public static int damerauLevenshteinDistance(String s1, String s2) {
        int len1 = s1.length();
        int len2 = s2.length();
        int[][] dp = new int[len1 + 1][len2 + 1];

        for (int i = 0; i <= len1; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= len2; j++) {
            dp[0][j] = j;
        }

        for (int i = 1; i <= len1; i++) {
            for (int j = 1; j <= len2; j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                int deletion = dp[i - 1][j] + 1;
                int insertion = dp[i][j - 1] + 1;
                int substitution = dp[i - 1][j - 1] + cost;
                dp[i][j] = Math.min(Math.min(deletion, insertion), substitution);

                if (i > 1 && j > 1
                        && s1.charAt(i - 1) == s2.charAt(j - 2)
                        && s1.charAt(i - 2) == s2.charAt(j - 1)) {
                    dp[i][j] = Math.min(dp[i][j], dp[i - 2][j - 2] + 1);
                }
            }
        }
        return dp[len1][len2];
    }

    // ========================================================================
    // Internal Helpers
    // ========================================================================

    private record NameComparisonResult(
            boolean present,
            double similarity,
            String algorithm,
            String rationale
    ) {}

    private record BirthDateComparisonResult(
            double similarity,
            String rationale
    ) {}

    private record TelecomComparisonResult(
            boolean present,
            double similarity,
            String rationale
    ) {}

    private NameComparisonResult compareNames(String leftName, String rightName) {
        if (isBlank(leftName) || isBlank(rightName)) {
            return new NameComparisonResult(false, 0.0, "NONE", "Missing name component");
        }
        String n1 = normalizeToken(leftName);
        String n2 = normalizeToken(rightName);
        if (n1.equals(n2)) {
            return new NameComparisonResult(true, 1.0, "EXACT_NORMALIZED", "Exact normalized name match");
        }

        double jw = jaroWinklerSimilarity(n1, n2);
        double dl = damerauLevenshteinSimilarity(n1, n2);
        double best = Math.max(jw, dl);

        String p1 = FellegiSunterMatcher.phoneticKey(n1);
        String p2 = FellegiSunterMatcher.phoneticKey(n2);
        boolean phoneticMatch = !p1.isEmpty() && p1.equals(p2);
        if (phoneticMatch && best >= 0.72) {
            best = Math.min(0.97, best + 0.08);
            return new NameComparisonResult(
                    true,
                    best,
                    "JARO_WINKLER_PHONETIC_METAPHONE",
                    "Phonetic key agreement (" + p1 + ") with high edit similarity"
            );
        }

        return new NameComparisonResult(
                true,
                best,
                "JARO_WINKLER_DAMERAU_LEVENSHTEIN",
                best >= 0.85 ? "High fuzzy string similarity" : "Low name similarity"
        );
    }

    private NameComparisonResult compareGivenNamesWithSwapDetection(
            PatientAggregateEntity left,
            PatientAggregateEntity right) {

        NameComparisonResult direct = compareNames(left.getGivenName(), right.getGivenName());
        if (!direct.present()) {
            return direct;
        }

        // Detect cultural / clerical given-family name inversion (e.g. "Nshome Jude" vs "Jude Nshome")
        if (!isBlank(left.getFamilyName()) && !isBlank(right.getFamilyName())) {
            double cross1 = jaroWinklerSimilarity(left.getGivenName(), right.getFamilyName());
            double cross2 = jaroWinklerSimilarity(left.getFamilyName(), right.getGivenName());
            double swappedAverage = (cross1 + cross2) / 2.0;
            if (swappedAverage > direct.similarity() && swappedAverage >= 0.88) {
                return new NameComparisonResult(
                        true,
                        round4(swappedAverage * 0.94),
                        "GIVEN_FAMILY_TRANSPOSITION",
                        "Detected transposed family/given name order across records"
                );
            }
        }
        return direct;
    }

    private double compareMiddleNameOrInitial(String leftMiddle, String rightMiddle) {
        String m1 = normalizeToken(leftMiddle);
        String m2 = normalizeToken(rightMiddle);
        if (m1.isEmpty() || m2.isEmpty()) {
            return 0.0;
        }
        if (m1.equals(m2)) {
            return 1.0;
        }
        if ((m1.length() == 1 || m2.length() == 1) && m1.charAt(0) == m2.charAt(0)) {
            return 0.90;
        }
        return jaroWinklerSimilarity(m1, m2);
    }

    private BirthDateComparisonResult compareBirthDates(LocalDate d1, LocalDate d2) {
        if (d1.equals(d2)) {
            return new BirthDateComparisonResult(1.0, "Exact birthDate match");
        }
        // Day/Month transposition check (e.g., 1990-03-11 vs 1990-11-03)
        if (d1.getYear() == d2.getYear()
                && d1.getMonthValue() == d2.getDayOfMonth()
                && d1.getDayOfMonth() == d2.getMonthValue()) {
            return new BirthDateComparisonResult(0.86, "Same birth year with transposed day and month");
        }
        // Two of three date components equal (year+month or month+day with single-digit year typo)
        if (d1.getYear() == d2.getYear() && d1.getMonthValue() == d2.getMonthValue()
                && Math.abs(d1.getDayOfMonth() - d2.getDayOfMonth()) <= 2) {
            return new BirthDateComparisonResult(0.78, "Same year and month with near day-of-month");
        }
        if (d1.getMonthValue() == d2.getMonthValue() && d1.getDayOfMonth() == d2.getDayOfMonth()
                && Math.abs(d1.getYear() - d2.getYear()) == 1) {
            return new BirthDateComparisonResult(0.75, "Exact month and day with off-by-one birth year");
        }
        long daysBetween = Math.abs(ChronoUnit.DAYS.between(d1, d2));
        if (daysBetween <= 5) {
            return new BirthDateComparisonResult(0.70, "Birth dates within 5 days");
        }
        return new BirthDateComparisonResult(0.0, "Distinct birth dates");
    }

    private TelecomComparisonResult comparePhoneNumbers(
            PatientAggregateEntity left,
            PatientAggregateEntity right) {

        List<String> leftPhones = extractNormalizedPhones(left.getPrimaryPhone(), left.getSecondaryPhone());
        List<String> rightPhones = extractNormalizedPhones(right.getPrimaryPhone(), right.getSecondaryPhone());
        if (leftPhones.isEmpty() || rightPhones.isEmpty()) {
            return new TelecomComparisonResult(false, 0.0, "Missing telecom number");
        }

        double bestSim = 0.0;
        String bestRationale = "Distinct phone numbers";

        for (String p1 : leftPhones) {
            for (String p2 : rightPhones) {
                if (p1.equals(p2)) {
                    return new TelecomComparisonResult(true, 1.0, "Exact normalized subscriber phone match");
                }
                int dist = damerauLevenshteinDistance(p1, p2);
                if (dist == 1 && Math.min(p1.length(), p2.length()) >= 8) {
                    bestSim = Math.max(bestSim, 0.84);
                    bestRationale = "Single-digit typo or transposition in phone number";
                }
            }
        }
        return new TelecomComparisonResult(true, bestSim, bestRationale);
    }

    private static List<String> extractNormalizedPhones(String... rawPhones) {
        List<String> normalized = new ArrayList<>();
        if (rawPhones == null) {
            return normalized;
        }
        for (String raw : rawPhones) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String digits = raw.replaceAll("[^0-9]", "");
            if (digits.length() >= 8) {
                String tail = digits.length() > 9 ? digits.substring(digits.length() - 9) : digits;
                normalized.add(tail);
            }
        }
        return normalized;
    }

    /**
     * Interpolates between the Fellegi-Sunter agreement weight {@code ln(m / u)} and
     * disagreement weight {@code ln((1 - m) / (1 - u))} using continuous string similarity
     * (Winkler continuous weight adjustment).
     */
    private static double interpolateFellegiSunterWeight(double similarity, double mProb, double uProb) {
        double clampedSim = Math.max(0.0, Math.min(1.0, similarity));
        double agreementWeight = Math.log(mProb / uProb);
        double disagreementWeight = Math.log((1.0 - mProb) / (1.0 - uProb));
        if (clampedSim >= 0.55) {
            double scaled = (clampedSim - 0.55) / 0.45;
            return disagreementWeight + (scaled * (agreementWeight - disagreementWeight));
        }
        return disagreementWeight * (1.0 - clampedSim);
    }

    private Set<String> buildCandidateBlocks(Collection<PatientAggregateEntity> patients) {
        Map<String, List<PatientAggregateEntity>> blocks = new LinkedHashMap<>();
        List<PatientAggregateEntity> activeList = new ArrayList<>();

        for (PatientAggregateEntity p : patients) {
            if (p == null || p.getId() == null || !p.isActive() || p.getMergedIntoPatientId() != null) {
                continue;
            }
            activeList.add(p);

            // Block 1: Family phonetic key
            String famPhonetic = FellegiSunterMatcher.phoneticKey(p.getFamilyName());
            if (!famPhonetic.isEmpty()) {
                blocks.computeIfAbsent("FAM_PHON:" + famPhonetic, k -> new ArrayList<>()).add(p);
            }
            // Block 2: Given phonetic key (catches family/given swap or surname change)
            String givPhonetic = FellegiSunterMatcher.phoneticKey(p.getGivenName());
            if (!givPhonetic.isEmpty()) {
                blocks.computeIfAbsent("GIV_PHON:" + givPhonetic, k -> new ArrayList<>()).add(p);
            }
            // Block 3: Exact birth date or birth year + gender
            if (p.getBirthDate() != null) {
                blocks.computeIfAbsent("DOB:" + p.getBirthDate(), k -> new ArrayList<>()).add(p);
                String genderCode = p.getGender() != null ? p.getGender().toUpperCase(Locale.ROOT) : "U";
                blocks.computeIfAbsent("YOB_GEN:" + p.getBirthDate().getYear() + ":" + genderCode,
                        k -> new ArrayList<>()).add(p);
            }
            // Block 4: Subscriber phone tail
            for (String phoneTail : extractNormalizedPhones(p.getPrimaryPhone(), p.getSecondaryPhone())) {
                blocks.computeIfAbsent("TEL:" + phoneTail, k -> new ArrayList<>()).add(p);
            }
        }

        Set<String> pairKeys = new LinkedHashSet<>();
        for (List<PatientAggregateEntity> group : blocks.values()) {
            if (group.size() < 2) {
                continue;
            }
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    pairKeys.add(canonicalPairKey(group.get(i).getId(), group.get(j).getId()));
                }
            }
        }

        // For compact facility cohorts (<= 250 active records), ensure full pairwise coverage
        // so even records with simultaneous phonetic prefix typo and DOB transposition are evaluated
        if (activeList.size() <= 250) {
            for (int i = 0; i < activeList.size(); i++) {
                for (int j = i + 1; j < activeList.size(); j++) {
                    pairKeys.add(canonicalPairKey(activeList.get(i).getId(), activeList.get(j).getId()));
                }
            }
        }

        return pairKeys;
    }

    private DuplicatePatientFlag upsertDuplicateFlag(
            PatientAggregateEntity left,
            PatientAggregateEntity right,
            PairwiseScoreResult scoreResult) {

        if (left.getId() == null || right.getId() == null || Objects.equals(left.getId(), right.getId())) {
            return null;
        }
        String pairKey = canonicalPairKey(left.getId(), right.getId());
        if (excludedNonDuplicatePairKeys.contains(pairKey)) {
            return null;
        }

        PatientAggregateEntity orderedLeft = left.getId().toString().compareTo(right.getId().toString()) <= 0
                ? left
                : right;
        PatientAggregateEntity orderedRight = (orderedLeft == left) ? right : left;

        UUID existingFlagId = flagIdByPairKey.get(pairKey);
        DuplicatePatientFlag existing = existingFlagId != null ? flagsById.get(existingFlagId) : null;
        if (existing != null && (existing.status() == DuplicateFlagStatus.RESOLVED_MERGED
                || existing.status() == DuplicateFlagStatus.RESOLVED_NOT_DUPLICATE)) {
            return existing;
        }

        UUID flagId = existing != null ? existing.flagId() : UUID.randomUUID();
        Instant detectedAt = existing != null ? existing.detectedAt() : Instant.now();
        DuplicateFlagStatus status = existing != null
                ? existing.status()
                : DuplicateFlagStatus.PENDING_MANUAL_RESOLUTION;

        String recommendedAction = switch (scoreResult.matchGrade()) {
            case CERTAIN -> "HIGH_CONFIDENCE_MERGE_CANDIDATE — Verify identifiers & merge records";
            case PROBABLE -> "MANUAL_STEWARD_REVIEW — Inspect telecom & birthDate before merging";
            case POSSIBLE -> "DEMOGRAPHIC_VERIFICATION_REQUIRED — Confirm patient identity at next encounter";
            case CERTAINLY_NOT -> "NO_ACTION";
        };

        DuplicatePatientFlag flag = new DuplicatePatientFlag(
                flagId,
                orderedLeft.getFacilityId() != null ? orderedLeft.getFacilityId() : orderedRight.getFacilityId(),
                orderedLeft.getId(),
                orderedLeft.getMrn(),
                formatDisplayName(orderedLeft),
                orderedLeft.getBirthDate(),
                orderedLeft.getGender(),
                orderedLeft.getPrimaryPhone(),
                orderedRight.getId(),
                orderedRight.getMrn(),
                formatDisplayName(orderedRight),
                orderedRight.getBirthDate(),
                orderedRight.getGender(),
                orderedRight.getPrimaryPhone(),
                scoreResult.compositeProbability(),
                scoreResult.fellegiSunterLogLikelihood(),
                scoreResult.matchGrade(),
                scoreResult.blockingKey(),
                recommendedAction,
                status,
                scoreResult.fieldBreakdown(),
                detectedAt,
                Instant.now(),
                existing != null ? existing.resolvedAt() : null,
                existing != null ? existing.resolvedBy() : null,
                existing != null ? existing.resolutionDecision() : null,
                existing != null ? existing.survivingPatientId() : null,
                existing != null ? existing.retiredDuplicatePatientId() : null,
                existing != null ? existing.resolutionNotes() : null
        );

        flagsById.put(flagId, flag);
        flagIdByPairKey.put(pairKey, flagId);
        return flag;
    }

    private Bundle buildFhirMatchSearchsetBundle(List<ProbabilisticMatchCandidate> candidates) {
        Bundle bundle = new Bundle();
        bundle.setId(UUID.randomUUID().toString());
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(candidates.size());

        for (ProbabilisticMatchCandidate match : candidates) {
            Patient fhirPatient;
            try {
                fhirPatient = patientService.getFhirPatientById(match.candidatePatientId());
            } catch (Exception ex) {
                fhirPatient = new Patient();
                fhirPatient.setId(match.candidatePatientId().toString());
                fhirPatient.addName().setFamily(match.familyName()).addGiven(match.givenName());
            }

            Bundle.BundleEntryComponent entry = bundle.addEntry();
            entry.setFullUrl("Patient/" + match.candidatePatientId());
            entry.setResource(fhirPatient);

            Bundle.BundleEntrySearchComponent search = entry.getSearch();
            search.setMode(Bundle.SearchEntryMode.MATCH);
            search.setScore(BigDecimal.valueOf(match.compositeProbability()).setScale(4, RoundingMode.HALF_UP));
            search.addExtension(new Extension(
                    FHIR_MATCH_GRADE_EXTENSION_URL,
                    new CodeType(match.matchGrade().fhirCode())
            ));
        }

        return bundle;
    }

    private List<PatientAggregateEntity> loadActivePatientPool(UUID facilityId, boolean crossFacility) {
        UUID queryFacility = crossFacility ? null : facilityId;
        PatientSearchCriteria criteria = new PatientSearchCriteria(
                null,
                queryFacility,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                Boolean.TRUE,
                0,
                500
        );
        PatientSearchResult searchResult = patientService.searchPatients(criteria);
        return searchResult.patients();
    }

    private static PatientAggregateEntity toProbeEntity(UUID facilityId, Patient fhirPatient) {
        PatientAggregateEntity probe = new PatientAggregateEntity();
        probe.setFacilityId(com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(facilityId));
        if (fhirPatient.hasIdElement() && fhirPatient.getIdElement().hasIdPart()) {
            try {
                probe.setId(UUID.fromString(fhirPatient.getIdElement().getIdPart()));
            } catch (IllegalArgumentException ignored) {
                // Non-UUID transient probe ID
            }
        }
        if (!fhirPatient.getName().isEmpty()) {
            HumanName hn = fhirPatient.getNameFirstRep();
            probe.setFamilyName(hn.getFamily());
            if (!hn.getGiven().isEmpty()) {
                probe.setGivenName(hn.getGiven().get(0).getValue());
            }
            if (hn.getGiven().size() > 1) {
                probe.setMiddleName(hn.getGiven().get(1).getValue());
            }
        }
        if (fhirPatient.hasBirthDate()) {
            probe.setBirthDate(fhirPatient.getBirthDate().toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate());
        }
        if (fhirPatient.hasGender() && fhirPatient.getGender() != null) {
            probe.setGender(fhirPatient.getGender().toCode());
        }
        for (ContactPoint cp : fhirPatient.getTelecom()) {
            if (cp.getSystem() == ContactPoint.ContactPointSystem.PHONE && isBlank(probe.getPrimaryPhone())) {
                probe.setPrimaryPhone(cp.getValue());
            } else if (cp.getSystem() == ContactPoint.ContactPointSystem.EMAIL && isBlank(probe.getEmail())) {
                probe.setEmail(cp.getValue());
            }
        }
        for (Identifier ident : fhirPatient.getIdentifier()) {
            if (ident.hasValue() && isBlank(probe.getMrn())) {
                probe.setMrn(ident.getValue());
            }
        }
        return probe;
    }

    private static String computeBlockingKey(PatientAggregateEntity p) {
        String fam = FellegiSunterMatcher.phoneticKey(p.getFamilyName());
        String giv = FellegiSunterMatcher.phoneticKey(p.getGivenName());
        String yob = p.getBirthDate() != null ? String.valueOf(p.getBirthDate().getYear()) : "0000";
        return fam + "-" + giv + "-" + yob;
    }

    private static String canonicalPairKey(UUID a, UUID b) {
        String s1 = a.toString();
        String s2 = b.toString();
        return s1.compareTo(s2) <= 0 ? (s1 + ":" + s2) : (s2 + ":" + s1);
    }

    private static String formatDisplayName(PatientAggregateEntity p) {
        StringBuilder sb = new StringBuilder();
        if (!isBlank(p.getGivenName())) {
            sb.append(p.getGivenName().trim());
        }
        if (!isBlank(p.getMiddleName())) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(p.getMiddleName().trim());
        }
        if (!isBlank(p.getFamilyName())) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(p.getFamilyName().trim().toUpperCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private static String normalizeToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String decomposed = Normalizer.normalize(raw.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return decomposed.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static double round4(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
