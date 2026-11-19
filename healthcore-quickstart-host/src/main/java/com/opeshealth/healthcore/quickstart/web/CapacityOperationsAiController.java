/*
 * =============================================================================
 * Opes HealthCore SDK — AI Patient Flow & Staff Scheduling Capacity Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.ai.operations.ClinicalCapacityPredictor;
import com.opeshealth.healthcore.ai.operations.ClinicalCapacityPredictor.CapacityAuditEvent;
import com.opeshealth.healthcore.ai.operations.ClinicalCapacityPredictor.CapacityForecastRequest;
import com.opeshealth.healthcore.ai.operations.ClinicalCapacityPredictor.CapacityForecastResult;
import com.opeshealth.healthcore.ai.operations.ClinicalCapacityPredictor.DeterministicCapacityMetrics;
import com.opeshealth.healthcore.ai.operations.ClinicalCapacityPredictor.RedactedCapacityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
 * REST Controller for ER/Clinic Patient Flow Forecasting &amp; Shift Staff Scheduling,
 * backed by {@link ClinicalCapacityPredictor} with full audit trail integration.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/ai/capacity")
@Tag(name = "Clinical AI — Capacity & Staff Scheduling Predictor",
        description = "ER/Clinic patient flow forecasting, surge detection, shift staffing recommendations, Safe-Harbor PHI scrubbing, and full audit trail integration")
public class CapacityOperationsAiController {

    public record CapacityBaselineResponse(
            int totalFhirResourcesProcessed,
            DeterministicCapacityMetrics deterministicMetrics,
            int totalPhiRedactions,
            Map<String, Integer> redactionCountsByCategory
    ) {}

    private final ClinicalCapacityPredictor capacityPredictor;

    public CapacityOperationsAiController(ClinicalCapacityPredictor capacityPredictor) {
        this.capacityPredictor = Objects.requireNonNull(capacityPredictor, "ClinicalCapacityPredictor is required");
    }

    @PostMapping(value = "/forecast", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Forecast ER/clinic patient flow and shift-level staff scheduling requirements with full audit trail integration")
    public ResponseEntity<CapacityForecastResult> forecastCapacityAndStaffing(
            @RequestBody CapacityForecastRequest request) {
        return ResponseEntity.status(201).body(capacityPredictor.forecastPatientFlowAndStaffing(request));
    }

    @PostMapping(value = "/baseline", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Compute deterministic ER/clinic surge tier, arrival trend slope, and shift staffing baselines with audit trail logging")
    public ResponseEntity<CapacityBaselineResponse> analyzeCapacityBaseline(
            @RequestBody CapacityForecastRequest request) {
        RedactedCapacityContext ctx = capacityPredictor.analyzeCapacityBaselineWithAudit(request);
        return ResponseEntity.ok(new CapacityBaselineResponse(
                ctx.totalFhirResourcesProcessed(),
                ctx.deterministicMetrics(),
                ctx.redactedPayload().totalRedactions(),
                ctx.redactedPayload().redactionCountsByCategory()
        ));
    }

    @GetMapping(value = "/audit-trail/{facilityId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Retrieve chronological operational capacity forecast audit trail for a healthcare facility")
    public ResponseEntity<List<CapacityAuditEvent>> getFacilityCapacityAuditTrail(@PathVariable UUID facilityId) {
        return ResponseEntity.ok(capacityPredictor.getAuditTrailForFacility(facilityId));
    }
}
