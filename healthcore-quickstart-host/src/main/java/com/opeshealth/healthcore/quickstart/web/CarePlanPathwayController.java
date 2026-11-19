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

import com.opeshealth.healthcore.quickstart.service.CarePlanPathwayService;
import com.opeshealth.healthcore.quickstart.service.CarePlanPathwayService.CareGapEvaluationSummary;
import com.opeshealth.healthcore.quickstart.service.CarePlanPathwayService.EnrollCarePlanRequest;
import com.opeshealth.healthcore.quickstart.service.CarePlanPathwayService.LongitudinalCarePlan;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * REST Controller for Longitudinal HL7 FHIR R4 {@code CarePlan}, {@code Goal}, {@code Task},
 * and Automated Care-Gap Closure.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/care-plans")
@Tag(name = "FHIR R4 Longitudinal CarePlans, Goals & Care-Gap Closure",
        description = "Evidence-based chronic disease & maternal CarePlans (Diabetes, Hypertension, HIV ART, TB DOT, ANC, Heart Failure), Task closure, and overdue care-gap detection")
public class CarePlanPathwayController {

    private final CarePlanPathwayService carePlanService;

    public CarePlanPathwayController(CarePlanPathwayService carePlanService) {
        this.carePlanService = Objects.requireNonNull(carePlanService, "CarePlanPathwayService is required");
    }

    @PostMapping(value = "/enroll", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Enroll a patient in an evidence-based longitudinal CarePlan pathway")
    public ResponseEntity<LongitudinalCarePlan> enrollPatient(
            @RequestBody EnrollCarePlanRequest request) {
        return ResponseEntity.status(201).body(carePlanService.enrollPatientInPathway(request));
    }

    @PostMapping(value = "/{carePlanId}/tasks/{taskId}/complete", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Mark a CarePlan milestone Task completed and evaluate Goal achievement")
    public ResponseEntity<LongitudinalCarePlan> completeTask(
            @PathVariable("carePlanId") UUID carePlanId,
            @PathVariable("taskId") UUID taskId,
            @RequestParam(value = "clinicianId", required = false) String clinicianId) {
        return ResponseEntity.ok(carePlanService.completeCarePlanTask(carePlanId, taskId, clinicianId));
    }

    @PostMapping(value = "/patient/{patientId}/evaluate-gaps", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Evaluate active CarePlans to identify overdue clinical care gaps")
    public ResponseEntity<CareGapEvaluationSummary> evaluateCareGaps(
            @PathVariable("patientId") UUID patientId,
            @RequestParam(value = "asOfDate", required = false) String asOfDateIso) {
        LocalDate asOf = (asOfDateIso != null && !asOfDateIso.isBlank()) ? LocalDate.parse(asOfDateIso.trim()) : null;
        return ResponseEntity.ok(carePlanService.evaluatePatientCareGaps(patientId, asOf));
    }

    @GetMapping(value = "/patient/{patientId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List all longitudinal CarePlans for a patient")
    public ResponseEntity<List<LongitudinalCarePlan>> listPatientCarePlans(
            @PathVariable("patientId") UUID patientId) {
        return ResponseEntity.ok(carePlanService.listPatientCarePlans(patientId));
    }
}
