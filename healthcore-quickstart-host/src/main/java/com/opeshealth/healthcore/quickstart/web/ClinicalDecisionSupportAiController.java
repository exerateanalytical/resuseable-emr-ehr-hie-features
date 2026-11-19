/*
 * =============================================================================
 * Opes HealthCore SDK — AI Clinical Decision Support REST Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.ai.cds.ClinicalDecisionEngine;
import com.opeshealth.healthcore.ai.cds.ClinicalDecisionEngine.ClinicalDecisionEvaluationResult;
import com.opeshealth.healthcore.ai.cds.ClinicalDecisionEngine.FhirCdsTriggerRequest;
import com.opeshealth.healthcore.quickstart.service.ClinicalDecisionSupportAiService;
import com.opeshealth.healthcore.quickstart.service.ClinicalDecisionSupportAiService.ClinicalCdsDraftResponse;
import com.opeshealth.healthcore.quickstart.service.ClinicalDecisionSupportAiService.ClinicalCdsRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * REST Controller for Capability 5: AI Clinical Decision Support &amp; Differential Assistant,
 * including real-time FHIR resource trigger alerts powered by {@link ClinicalDecisionEngine}.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/ai/cds")
@Tag(name = "Clinical AI — Clinical Decision Support & Differential Assistant",
        description = "Human-in-the-loop drug-disease, drug-interaction, pharmacogenomics, sepsis cues, and differential workup synthesis")
public class ClinicalDecisionSupportAiController {

    private final ClinicalDecisionSupportAiService cdsAiService;
    private final ClinicalDecisionEngine clinicalDecisionEngine;

    public ClinicalDecisionSupportAiController(
            ClinicalDecisionSupportAiService cdsAiService,
            ClinicalDecisionEngine clinicalDecisionEngine) {
        this.cdsAiService = Objects.requireNonNull(cdsAiService);
        this.clinicalDecisionEngine = Objects.requireNonNull(clinicalDecisionEngine);
    }

    @PostMapping(value = "/evaluate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Evaluate patient vitals, labs, medications, allergies, and PGx for advisory CDS alerts and differentials")
    public ResponseEntity<ClinicalCdsDraftResponse> evaluateCds(@RequestBody ClinicalCdsRequest request) {
        return ResponseEntity.status(201).body(cdsAiService.evaluateClinicalDecisionSupport(request));
    }

    @PostMapping(value = "/fhir-trigger", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Evaluate real-time HL7 FHIR R4 resource triggers for evidence-based clinical alerts and drug-interaction warnings with mandatory Safe-Harbor PHI redaction")
    public ResponseEntity<ClinicalDecisionEvaluationResult> evaluateFhirTrigger(
            @RequestBody FhirCdsTriggerRequest request) {
        return ResponseEntity.status(201).body(clinicalDecisionEngine.evaluateFhirTrigger(request));
    }
}
