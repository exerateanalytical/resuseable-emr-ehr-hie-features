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

import com.opeshealth.healthcore.quickstart.service.ClinicalQualityMeasureService;
import com.opeshealth.healthcore.quickstart.service.ClinicalQualityMeasureService.EvaluateMeasureRequest;
import com.opeshealth.healthcore.quickstart.service.ClinicalQualityMeasureService.MeasureEvaluationResult;
import com.opeshealth.healthcore.quickstart.service.ClinicalQualityMeasureService.QualityMeasureDefinition;
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
import java.util.Objects;
import java.util.UUID;

/**
 * HL7 FHIR R4 Electronic Clinical Quality Measures (eCQM / CQL) REST Controller
 * exposing {@code /api/v1/fhir/Measure} and {@code /api/v1/fhir/Measure/{id}/$evaluate-measure}.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/fhir")
@Tag(name = "FHIR R4 Clinical Quality Measures (eCQM / $evaluate-measure)",
        description = "HEDIS, CMS eCQM, and WHO quality indicator definitions and Measure/{id}/$evaluate-measure FHIR MeasureReport generation")
public class ClinicalQualityMeasureController {

    private final ClinicalQualityMeasureService cqmService;

    public ClinicalQualityMeasureController(ClinicalQualityMeasureService cqmService) {
        this.cqmService = Objects.requireNonNull(cqmService, "ClinicalQualityMeasureService is required");
    }

    @GetMapping(value = "/Measure", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List registered HL7 FHIR R4 eCQM / WHO Quality Measure definitions")
    public ResponseEntity<List<QualityMeasureDefinition>> listQualityMeasures() {
        return ResponseEntity.ok(cqmService.listQualityMeasures());
    }

    @PostMapping(value = "/Measure", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Register a custom HL7 FHIR R4 eCQM / CQL Quality Measure definition")
    public ResponseEntity<QualityMeasureDefinition> registerQualityMeasure(
            @RequestBody QualityMeasureDefinition definition) {
        return ResponseEntity.status(201).body(cqmService.registerQualityMeasure(definition));
    }

    @PostMapping(value = "/Measure/{measureId}/$evaluate-measure", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Evaluate a patient cohort against an eCQM Measure and generate a FHIR R4 MeasureReport")
    public ResponseEntity<MeasureEvaluationResult> evaluateMeasure(
            @PathVariable("measureId") String measureId,
            @RequestBody EvaluateMeasureRequest request) {
        return ResponseEntity.ok(cqmService.evaluateMeasure(measureId, request));
    }

    @GetMapping(value = "/MeasureReport/{reportId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Retrieve a previously generated FHIR R4 MeasureReport evaluation")
    public ResponseEntity<MeasureEvaluationResult> getMeasureReport(@PathVariable("reportId") UUID reportId) {
        return ResponseEntity.ok(cqmService.getMeasureReport(reportId));
    }
}
