/*
 * =============================================================================
 * Opes HealthCore SDK — Calibrated Clinical Decision Engine REST Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.quickstart.service.CalibratedClinicalDecisionService;
import com.opeshealth.healthcore.quickstart.service.CalibratedClinicalDecisionService.CalibratedDecisionResponse;
import com.opeshealth.healthcore.quickstart.service.CalibratedClinicalDecisionService.EmergencyTriageDecisionRequest;
import com.opeshealth.healthcore.quickstart.service.CalibratedClinicalDecisionService.PriorAuthPolicyRubricRequest;
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
 * REST Controller for Capability 7: Calibrated Clinical Triage &amp; Policy Decision Engine
 * powered by {@code gemini-decision-1} ({@code POST /v1beta/decisions}).
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/ai/decisions")
@Tag(name = "Clinical AI — Calibrated Triage & Policy Decisions (gemini-decision-1)",
        description = "Calibrated yes/no (noul), ESI 1-5 acuity routing (choice), and medical necessity rubric (score) decisions with zero output tokens")
public class CalibratedClinicalDecisionController {

    private final CalibratedClinicalDecisionService decisionService;

    public CalibratedClinicalDecisionController(CalibratedClinicalDecisionService decisionService) {
        this.decisionService = Objects.requireNonNull(decisionService);
    }

    @PostMapping(value = "/emergency-triage", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Run calibrated ESI 1-5 triage routing, sepsis escalation gate, and deterioration scoring")
    public ResponseEntity<CalibratedDecisionResponse> evaluateEmergencyTriage(
            @RequestBody EmergencyTriageDecisionRequest request) {
        return ResponseEntity.status(201).body(decisionService.evaluateEmergencyTriage(request));
    }

    @PostMapping(value = "/prior-auth-rubric", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Run calibrated prior-authorization medical necessity yes/no gate, rubric score, and UM routing")
    public ResponseEntity<CalibratedDecisionResponse> evaluatePriorAuthRubric(
            @RequestBody PriorAuthPolicyRubricRequest request) {
        return ResponseEntity.status(201).body(decisionService.evaluatePriorAuthPolicyRubric(request));
    }
}
