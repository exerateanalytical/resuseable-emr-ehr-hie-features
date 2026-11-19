/*
 * =============================================================================
 * Opes HealthCore SDK — AI Predictive Readmission & Disease Progression Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.ai.predictive.ClinicalPredictiveEngine;
import com.opeshealth.healthcore.ai.predictive.ClinicalPredictiveEngine.ClinicalPredictiveForecastResult;
import com.opeshealth.healthcore.ai.predictive.ClinicalPredictiveEngine.LongitudinalFhirSignals;
import com.opeshealth.healthcore.ai.predictive.ClinicalPredictiveEngine.PredictiveForecastRequest;
import com.opeshealth.healthcore.ai.predictive.ClinicalPredictiveEngine.RedactedLongitudinalContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * REST Controller for Predictive Clinical Risk &amp; Chronic Disease Progression Analytics,
 * backed by {@link ClinicalPredictiveEngine}.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/ai/predictive")
@Tag(name = "Clinical AI — Predictive Readmission & Disease Progression",
        description = "Longitudinal HL7 FHIR R4 readmission risk forecasting (LACE + Gemini) and chronic disease trajectory analysis with mandatory Safe-Harbor PHI redaction")
public class PredictiveAnalyticsAiController {

    public record BaselineSignalsRequest(
            List<String> historicalFhirResourcesJson,
            List<String> socialDeterminantsOfHealth
    ) {}

    public record BaselineSignalsResponse(
            int totalResourcesProcessed,
            Map<String, Integer> resourceCountsByType,
            LongitudinalFhirSignals baselineLongitudinalSignals,
            int totalPhiRedactions,
            Map<String, Integer> redactionCountsByCategory
    ) {}

    private final ClinicalPredictiveEngine predictiveEngine;

    public PredictiveAnalyticsAiController(ClinicalPredictiveEngine predictiveEngine) {
        this.predictiveEngine = Objects.requireNonNull(predictiveEngine, "ClinicalPredictiveEngine is required");
    }

    @PostMapping(value = "/forecast", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Forecast patient readmission risk and chronic disease progression trends from historical FHIR R4 data with Safe-Harbor PHI redaction")
    public ResponseEntity<ClinicalPredictiveForecastResult> forecastReadmissionAndProgression(
            @RequestBody PredictiveForecastRequest request) {
        return ResponseEntity.status(201).body(predictiveEngine.forecastReadmissionAndDiseaseProgression(request));
    }

    @PostMapping(value = "/baseline-signals", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Extract deterministic LACE readmission baseline and serial FHIR biomarker progression trends with Safe-Harbor PHI redaction")
    public ResponseEntity<BaselineSignalsResponse> extractBaselineSignals(
            @RequestBody BaselineSignalsRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("BaselineSignalsRequest is required.");
        }
        RedactedLongitudinalContext ctx = predictiveEngine.prepareRedactedLongitudinalContext(
                request.historicalFhirResourcesJson(),
                request.socialDeterminantsOfHealth()
        );
        return ResponseEntity.ok(new BaselineSignalsResponse(
                ctx.totalResourcesProcessed(),
                ctx.resourceCountsByType(),
                ctx.longitudinalSignals(),
                ctx.redactedPayload().totalRedactions(),
                ctx.redactedPayload().redactionCountsByCategory()
        ));
    }
}
