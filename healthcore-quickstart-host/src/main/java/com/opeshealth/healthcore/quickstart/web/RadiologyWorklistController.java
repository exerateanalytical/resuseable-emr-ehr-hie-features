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

import com.opeshealth.healthcore.quickstart.service.RadiologyWorklistService;
import com.opeshealth.healthcore.quickstart.service.RadiologyWorklistService.ImagingModality;
import com.opeshealth.healthcore.quickstart.service.RadiologyWorklistService.ModalityWorklistItem;
import com.opeshealth.healthcore.quickstart.service.RadiologyWorklistService.MppsStatus;
import com.opeshealth.healthcore.quickstart.service.RadiologyWorklistService.MppsUpdateRequest;
import com.opeshealth.healthcore.quickstart.service.RadiologyWorklistService.RadiologyDiagnosticReport;
import com.opeshealth.healthcore.quickstart.service.RadiologyWorklistService.RadiologyReportSignOffRequest;
import com.opeshealth.healthcore.quickstart.service.RadiologyWorklistService.ScheduleImagingOrderRequest;
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

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * REST Controller for Radiology &amp; PACS DICOM Modality Worklist (MWL), MPPS,
 * Structured FHIR {@code DiagnosticReport} Sign-Off, and Critical Findings Escalation.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/radiology")
@Tag(name = "FHIR R4 Radiology Modality Worklist (DICOM MWL / MPPS & Critical Findings)",
        description = "Imaging ServiceRequest scheduling into DICOM MWL, MPPS acquisition updates, structured FHIR DiagnosticReport sign-off, and closed-loop critical findings acknowledgment")
public class RadiologyWorklistController {

    private final RadiologyWorklistService radiologyService;

    public RadiologyWorklistController(RadiologyWorklistService radiologyService) {
        this.radiologyService = Objects.requireNonNull(radiologyService, "RadiologyWorklistService is required");
    }

    @PostMapping(value = "/mwl/orders", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Schedule an imaging order into the DICOM Modality Worklist (MWL)")
    public ResponseEntity<ModalityWorklistItem> scheduleOrder(
            @RequestBody ScheduleImagingOrderRequest request) {
        return ResponseEntity.status(201).body(radiologyService.scheduleModalityWorklistOrder(request));
    }

    @GetMapping(value = "/mwl/orders", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Query scheduled DICOM Modality Worklist items by modality and MPPS status")
    public ResponseEntity<List<ModalityWorklistItem>> queryWorklist(
            @RequestParam(value = "modality", required = false) ImagingModality modality,
            @RequestParam(value = "status", required = false) MppsStatus status) {
        return ResponseEntity.ok(radiologyService.queryModalityWorklist(modality, status));
    }

    @PostMapping(value = "/mwl/orders/{orderId}/mpps", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update DICOM Modality Performed Procedure Step (MPPS) acquisition state")
    public ResponseEntity<ModalityWorklistItem> updateMpps(
            @PathVariable("orderId") UUID orderId,
            @RequestBody MppsUpdateRequest request) {
        return ResponseEntity.ok(radiologyService.updateMppsStatus(orderId, request));
    }

    @PostMapping(value = "/reports", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Sign off a structured FHIR DiagnosticReport and trigger critical finding escalation if flagged")
    public ResponseEntity<RadiologyDiagnosticReport> signReport(
            @RequestBody RadiologyReportSignOffRequest request) {
        return ResponseEntity.status(201).body(radiologyService.signRadiologyReport(request));
    }

    @PostMapping(value = "/reports/{reportId}/acknowledge-critical", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Close the safety loop by acknowledging a critical radiology finding")
    public ResponseEntity<RadiologyDiagnosticReport> acknowledgeCriticalFinding(
            @PathVariable("reportId") UUID reportId,
            @RequestParam("clinicianId") String clinicianId) {
        return ResponseEntity.ok(radiologyService.acknowledgeCriticalFinding(reportId, clinicianId));
    }

    @GetMapping(value = "/reports/critical-unacknowledged", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List critical radiology findings awaiting ordering clinician acknowledgment")
    public ResponseEntity<List<RadiologyDiagnosticReport>> listUnacknowledgedCritical() {
        return ResponseEntity.ok(radiologyService.listUnacknowledgedCriticalFindings());
    }
}
