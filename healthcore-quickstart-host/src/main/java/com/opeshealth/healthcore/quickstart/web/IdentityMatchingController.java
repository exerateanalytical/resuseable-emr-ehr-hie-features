/*
 * Copyright (c) 2026 Opes Health Systems (https://www.opeshealthsystems.com)
 *
 * Author: Jude Nshome — Opes Health Systems
 * Email: nshome@opesware.com
 * Organization: Opes Health Systems, Douala, Cameroon
 *
 * Licensed under the Opes HealthCore Enterprise SDK License.
 */
package com.opeshealth.healthcore.quickstart.web;

import ca.uhn.fhir.context.FhirContext;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientAggregateEntity;
import com.opeshealth.healthcore.quickstart.service.IdentityMatchingService;
import com.opeshealth.healthcore.quickstart.service.IdentityMatchingService.DuplicateDatabaseScanSummary;
import com.opeshealth.healthcore.quickstart.service.IdentityMatchingService.DuplicateFlagStatus;
import com.opeshealth.healthcore.quickstart.service.IdentityMatchingService.DuplicatePatientFlag;
import com.opeshealth.healthcore.quickstart.service.IdentityMatchingService.FhirMatchEvaluationResult;
import com.opeshealth.healthcore.quickstart.service.IdentityMatchingService.ManualResolutionRequest;
import com.opeshealth.healthcore.quickstart.service.IdentityMatchingService.PairwiseScoreResult;
import com.opeshealth.healthcore.service.PatientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * HL7 FHIR R4 {@code Patient/$match} &amp; Probabilistic MPI Duplicate Resolution REST Controller.
 *
 * <p>Exposes probabilistic record linkage endpoints for detecting duplicate patient records
 * across the FHIR database and resolving flagged duplicate pairs via manual HIM steward workflow.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@Tag(name = "FHIR R4 Patient Identity Matching & Duplicate Resolution",
        description = "Probabilistic Fellegi-Sunter & Jaro-Winkler Patient/$match, database duplicate scanning, and manual resolution queue")
public class IdentityMatchingController {

    private static final String FHIR_JSON = "application/fhir+json";
    private static final FhirContext FHIR_R4 = FhirContext.forR4();

    public record DemographicMatchProbeRequest(
            UUID patientId,
            UUID facilityId,
            String mrn,
            String familyName,
            String givenName,
            String middleName,
            LocalDate birthDate,
            String gender,
            String primaryPhone,
            String email,
            Boolean onlyCertainMatches,
            Integer count,
            Boolean autoFlagMatches
    ) {}

    private final IdentityMatchingService identityMatchingService;
    private final PatientService patientService;

    public IdentityMatchingController(
            IdentityMatchingService identityMatchingService,
            PatientService patientService) {
        this.identityMatchingService = Objects.requireNonNull(identityMatchingService, "IdentityMatchingService is required");
        this.patientService = Objects.requireNonNull(patientService, "PatientService is required");
    }

    /**
     * Standard HL7 FHIR R4 {@code POST /api/v1/fhir/Patient/$match} operation accepting either a
     * FHIR {@code Parameters} resource (with {@code resource}, {@code onlyCertainMatches}, {@code count})
     * or a direct FHIR {@code Patient} resource payload.
     */
    @PostMapping(
            value = "/api/v1/fhir/Patient/$match",
            consumes = {FHIR_JSON, MediaType.APPLICATION_JSON_VALUE},
            produces = {FHIR_JSON, MediaType.APPLICATION_JSON_VALUE}
    )
    @Operation(
            summary = "HL7 FHIR R4 Patient/$match probabilistic operation",
            description = "Evaluates a probe FHIR R4 Patient or Parameters payload using Fellegi-Sunter and Jaro-Winkler similarity and returns a FHIR R4 searchset Bundle with match-grade extensions."
    )
    public ResponseEntity<String> fhirPatientMatchOperation(
            @RequestHeader(value = "X-Facility-Id", required = false) UUID facilityHeader,
            @RequestParam(value = "onlyCertainMatches", defaultValue = "false") boolean onlyCertainParam,
            @RequestParam(value = "count", defaultValue = "20") int countParam,
            @RequestParam(value = "autoFlag", defaultValue = "true") boolean autoFlagParam,
            @RequestBody String fhirPayloadJson,
            HttpServletRequest servletRequest) {

        if (fhirPayloadJson == null || fhirPayloadJson.isBlank()) {
            throw new IllegalArgumentException("FHIR R4 Parameters or Patient JSON payload is required for Patient/$match");
        }

        IBaseResource parsed = FHIR_R4.newJsonParser().parseResource(fhirPayloadJson);
        Patient probePatient = null;
        boolean onlyCertain = onlyCertainParam;
        int count = countParam;

        if (parsed instanceof Parameters params) {
            for (Parameters.ParametersParameterComponent part : params.getParameter()) {
                if ("resource".equals(part.getName()) && part.getResource() instanceof Patient p) {
                    probePatient = p;
                } else if ("onlyCertainMatches".equals(part.getName()) && part.getValue() instanceof BooleanType bt) {
                    onlyCertain = bt.booleanValue();
                } else if ("count".equals(part.getName()) && part.getValue() instanceof IntegerType it) {
                    count = it.getValue();
                }
            }
        } else if (parsed instanceof Patient p) {
            probePatient = p;
        }

        if (probePatient == null) {
            throw new IllegalArgumentException("Patient/$match payload must provide a FHIR R4 Patient resource");
        }

        FhirMatchEvaluationResult result = identityMatchingService.evaluatePatientMatch(
                facilityHeader,
                probePatient,
                onlyCertain,
                count,
                autoFlagParam,
                servletRequest
        );

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(FHIR_JSON))
                .body(result.fhirSearchsetBundleJson());
    }

    /**
     * Structured JSON probabilistic match endpoint returning both the field-by-field
     * Fellegi-Sunter log-likelihood breakdown and the serialized FHIR R4 {@code searchset} Bundle.
     */
    @PostMapping(
            value = "/api/v1/identity-matching/$match",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "Evaluate probabilistic patient identity match with field-level score breakdown",
            description = "Scores candidate patient records using Fellegi-Sunter log-likelihood ratios, Jaro-Winkler, and Damerau-Levenshtein similarity."
    )
    public ResponseEntity<FhirMatchEvaluationResult> evaluateDemographicProbeMatch(
            @RequestHeader(value = "X-Facility-Id", required = false) UUID facilityHeader,
            @RequestBody DemographicMatchProbeRequest request,
            HttpServletRequest servletRequest) {

        if (request == null) {
            throw new IllegalArgumentException("DemographicMatchProbeRequest body is required");
        }
        UUID resolvedFacility = request.facilityId() != null ? request.facilityId() : facilityHeader;

        PatientAggregateEntity probe = new PatientAggregateEntity();
        probe.setId(request.patientId());
        probe.setFacilityId(resolvedFacility != null ? resolvedFacility : PatientService.DEFAULT_FACILITY_ID);
        probe.setMrn(request.mrn());
        probe.setFamilyName(request.familyName());
        probe.setGivenName(request.givenName());
        probe.setMiddleName(request.middleName());
        probe.setBirthDate(request.birthDate());
        probe.setGender(request.gender());
        probe.setPrimaryPhone(request.primaryPhone());
        probe.setEmail(request.email());

        FhirMatchEvaluationResult result = identityMatchingService.evaluateEntityMatch(
                resolvedFacility,
                probe,
                Boolean.TRUE.equals(request.onlyCertainMatches()),
                request.count() != null ? request.count() : 20,
                request.autoFlagMatches() == null || request.autoFlagMatches(),
                servletRequest
        );
        return ResponseEntity.ok(result);
    }

    /**
     * Compares two specific existing FHIR {@code Patient} records by UUID and returns their
     * exact probabilistic field-by-field Fellegi-Sunter and Jaro-Winkler breakdown.
     */
    @GetMapping(
            value = "/api/v1/identity-matching/compare",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "Compare two existing FHIR Patient records probabilistically",
            description = "Computes the pairwise Fellegi-Sunter log-likelihood and Jaro-Winkler similarity between two Patient IDs."
    )
    public ResponseEntity<PairwiseScoreResult> compareTwoPatients(
            @RequestParam("leftPatientId") UUID leftPatientId,
            @RequestParam("rightPatientId") UUID rightPatientId) {

        PatientAggregateEntity left = patientService.getPatientById(leftPatientId);
        PatientAggregateEntity right = patientService.getPatientById(rightPatientId);
        return ResponseEntity.ok(identityMatchingService.computeProbabilisticScore(left, right));
    }

    /**
     * Triggers an automated database-wide probabilistic scan across active FHIR {@code Patient}
     * records, flagging potential duplicate pairs for manual steward resolution.
     */
    @PostMapping(
            value = "/api/v1/identity-matching/scan",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "Scan FHIR database for potential duplicate patient records",
            description = "Executes multi-pass phonetic/DOB/telecom blocking and Fellegi-Sunter scoring across the FHIR Patient database and flags duplicate candidates for manual resolution."
    )
    public ResponseEntity<DuplicateDatabaseScanSummary> scanDatabaseForDuplicates(
            @RequestHeader(value = "X-Facility-Id", required = false) UUID facilityHeader,
            @RequestParam(value = "facilityId", required = false) UUID facilityParam,
            @RequestParam(value = "crossFacility", defaultValue = "true") boolean crossFacility,
            @RequestParam(value = "threshold", required = false) Double threshold,
            HttpServletRequest servletRequest) {

        UUID resolvedFacility = facilityParam != null ? facilityParam : facilityHeader;
        DuplicateDatabaseScanSummary summary = identityMatchingService.scanDatabaseForDuplicates(
                resolvedFacility,
                crossFacility,
                threshold,
                servletRequest
        );
        return ResponseEntity.ok(summary);
    }

    /**
     * Lists potential duplicate patient pairs flagged for manual resolution.
     */
    @GetMapping(
            value = "/api/v1/identity-matching/flags",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "List flagged duplicate patient pairs awaiting manual resolution",
            description = "Returns flagged duplicate patient pairs ordered by composite match probability descending."
    )
    public ResponseEntity<List<DuplicatePatientFlag>> listDuplicateFlags(
            @RequestHeader(value = "X-Facility-Id", required = false) UUID facilityHeader,
            @RequestParam(value = "facilityId", required = false) UUID facilityParam,
            @RequestParam(value = "status", required = false) DuplicateFlagStatus status) {

        UUID resolvedFacility = facilityParam != null ? facilityParam : facilityHeader;
        return ResponseEntity.ok(identityMatchingService.listDuplicateFlags(resolvedFacility, status));
    }

    /**
     * Retrieves a specific flagged duplicate patient pair and its field-by-field probabilistic breakdown.
     */
    @GetMapping(
            value = "/api/v1/identity-matching/flags/{flagId}",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "Get a flagged duplicate patient pair by flagId",
            description = "Returns the full Fellegi-Sunter and Jaro-Winkler field breakdown and current resolution state."
    )
    public ResponseEntity<DuplicatePatientFlag> getDuplicateFlag(@PathVariable("flagId") UUID flagId) {
        return ResponseEntity.ok(identityMatchingService.getDuplicateFlag(flagId));
    }

    /**
     * Manually resolves a flagged duplicate patient pair (merge duplicate into surviving record,
     * dismiss as distinct non-duplicate, or defer for additional verification).
     */
    @PostMapping(
            value = "/api/v1/identity-matching/flags/{flagId}/resolve",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "Resolve a flagged duplicate patient pair manually",
            description = "Executes HIM data steward resolution: CONFIRM_DUPLICATE_AND_MERGE, CONFIRM_DISTINCT_NOT_DUPLICATE, or DEFER_ADDITIONAL_VERIFICATION."
    )
    public ResponseEntity<DuplicatePatientFlag> resolveDuplicateFlag(
            @PathVariable("flagId") UUID flagId,
            @RequestBody ManualResolutionRequest request,
            HttpServletRequest servletRequest) {

        return ResponseEntity.ok(identityMatchingService.resolveDuplicateFlag(flagId, request, servletRequest));
    }
}
