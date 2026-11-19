/*
 * =============================================================================
 * Opes HealthCore SDK — Multilingual Patient Discharge Coach REST Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.ai.coach.PatientHealthCoachEngine;
import com.opeshealth.healthcore.ai.coach.PatientHealthCoachEngine.ClinicianOversightDecisionResult;
import com.opeshealth.healthcore.ai.coach.PatientHealthCoachEngine.MedicationAdherenceSummary;
import com.opeshealth.healthcore.ai.coach.PatientHealthCoachEngine.PatientCoachingDraftResult;
import com.opeshealth.healthcore.ai.coach.PatientHealthCoachEngine.PatientCoachingRequest;
import com.opeshealth.healthcore.ai.coach.PatientHealthCoachEngine.RedactedCoachingContext;
import com.opeshealth.healthcore.ai.coach.PatientHealthCoachEngine.WellnessProfileSignals;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.AttestationCommand;
import com.opeshealth.healthcore.quickstart.service.PatientDischargeCoachAiService;
import com.opeshealth.healthcore.quickstart.service.PatientDischargeCoachAiService.PatientCoachDraftResponse;
import com.opeshealth.healthcore.quickstart.service.PatientDischargeCoachAiService.PatientCoachRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * REST Controller for Capability 8: Multilingual Patient Discharge, Longitudinal Wellness &amp;
 * Medication Adherence Coach (Text + Voice), backed by {@link PatientHealthCoachEngine}.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/ai/coach")
@Tag(name = "Clinical AI — Multilingual Patient Health, Adherence & Discharge Coach",
        description = "Longitudinal FHIR wellness & medication adherence coaching (PatientHealthCoachEngine), plain-language EN/FR discharge plans, optional WAV voice synthesis, and mandatory clinician oversight sign-off")
public class PatientDischargeCoachAiController {

    public record LongitudinalCoachingSignalsRequest(
            List<String> longitudinalFhirResourcesJson,
            List<String> patientReportedBarriers
    ) {}

    public record LongitudinalCoachingSignalsResponse(
            int totalResourcesProcessed,
            Map<String, Integer> resourceCountsByType,
            MedicationAdherenceSummary adherenceSummary,
            WellnessProfileSignals wellnessSignals,
            int totalPhiRedactions,
            Map<String, Integer> redactionCountsByCategory
    ) {}

    private final PatientDischargeCoachAiService coachService;
    private final PatientHealthCoachEngine healthCoachEngine;

    public PatientDischargeCoachAiController(
            PatientDischargeCoachAiService coachService,
            PatientHealthCoachEngine healthCoachEngine) {
        this.coachService = Objects.requireNonNull(coachService);
        this.healthCoachEngine = Objects.requireNonNull(healthCoachEngine);
    }

    @PostMapping(value = "/discharge-plan", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Generate plain-language multilingual discharge instructions with optional WAV voice synthesis")
    public ResponseEntity<PatientCoachDraftResponse> generateDischargeCoaching(@RequestBody PatientCoachRequest request) {
        return ResponseEntity.status(201).body(coachService.generateDischargeCoaching(request));
    }

    @PostMapping(value = "/longitudinal-coaching", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Generate personalized longitudinal wellness & medication adherence coaching prompts quarantined for mandatory clinician oversight")
    public ResponseEntity<PatientCoachingDraftResult> generateLongitudinalCoaching(
            @RequestBody PatientCoachingRequest request) {
        return ResponseEntity.status(201).body(healthCoachEngine.generatePersonalizedCoachingDraft(request));
    }

    @PostMapping(value = "/longitudinal-signals", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Analyze longitudinal FHIR data for medication adherence gaps, wellness opportunities, and clinician escalation cues with Safe-Harbor PHI redaction")
    public ResponseEntity<LongitudinalCoachingSignalsResponse> analyzeLongitudinalSignals(
            @RequestBody LongitudinalCoachingSignalsRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("LongitudinalCoachingSignalsRequest is required.");
        }
        RedactedCoachingContext ctx = healthCoachEngine.prepareRedactedCoachingContext(
                request.longitudinalFhirResourcesJson(),
                request.patientReportedBarriers()
        );
        return ResponseEntity.ok(new LongitudinalCoachingSignalsResponse(
                ctx.totalResourcesProcessed(),
                ctx.resourceCountsByType(),
                ctx.adherenceSummary(),
                ctx.wellnessSignals(),
                ctx.redactedPayload().totalRedactions(),
                ctx.redactedPayload().redactionCountsByCategory()
        ));
    }

    @PostMapping(value = "/drafts/{draftId}/oversight", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Clinician oversight gate: approve (with optional edits) or reject a patient coaching draft prior to patient release")
    public ResponseEntity<ClinicianOversightDecisionResult> attestCoachingDraft(
            @PathVariable UUID draftId,
            @RequestBody AttestationCommand oversightCommand) {
        return ResponseEntity.ok(healthCoachEngine.approveOrRejectCoachingDraft(draftId, oversightCommand));
    }
}
