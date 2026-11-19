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

import com.opeshealth.healthcore.quickstart.service.FhirChartPortabilityService;
import com.opeshealth.healthcore.quickstart.service.FhirChartPortabilityService.ChartEverythingExportResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * HL7 FHIR R4 Chart Portability REST Controller ({@code Patient/{id}/$everything},
 * {@code Encounter/{id}/$everything}, and Patient Compartment Search).
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/fhir")
@Tag(name = "FHIR R4 Chart Portability ($everything & Compartments)",
        description = "HL7 FHIR R4 Patient/{id}/$everything, Encounter/{id}/$everything, and Patient Compartment Search with DS4P privacy enforcement")
public class FhirChartPortabilityController {

    private static final String FHIR_JSON = "application/fhir+json";

    private final FhirChartPortabilityService portabilityService;

    public FhirChartPortabilityController(FhirChartPortabilityService portabilityService) {
        this.portabilityService = Objects.requireNonNull(portabilityService, "FhirChartPortabilityService is required");
    }

    @GetMapping(value = "/Patient/{id}/$everything", produces = {MediaType.APPLICATION_JSON_VALUE, FHIR_JSON})
    @Operation(summary = "Export a patient's complete longitudinal chart via HL7 FHIR R4 Patient/{id}/$everything")
    public ResponseEntity<ChartEverythingExportResponse> exportPatientEverything(
            @PathVariable("id") UUID patientId,
            @RequestHeader(value = "X-Actor-Id", required = false) String actorId,
            @RequestHeader(value = "X-Break-The-Glass-Token", required = false) String btgHeader,
            @RequestParam(value = "breakTheGlassToken", required = false) String btgParam,
            @RequestParam(value = "_type", required = false) String resourceTypesCsv,
            @RequestParam(value = "_since", required = false) String sinceIso) {
        String resolvedBtg = (btgParam != null && !btgParam.isBlank()) ? btgParam : btgHeader;
        Set<String> allowedTypes = (resourceTypesCsv == null || resourceTypesCsv.isBlank())
                ? Set.of()
                : Arrays.stream(resourceTypesCsv.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toSet());
        Instant since = (sinceIso != null && !sinceIso.isBlank()) ? Instant.parse(sinceIso.trim()) : null;
        return ResponseEntity.ok(portabilityService.exportPatientEverything(
                patientId, actorId, resolvedBtg, allowedTypes, since));
    }

    @GetMapping(value = "/Encounter/{id}/$everything", produces = {MediaType.APPLICATION_JSON_VALUE, FHIR_JSON})
    @Operation(summary = "Export an encounter-scoped chart bundle via HL7 FHIR R4 Encounter/{id}/$everything")
    public ResponseEntity<ChartEverythingExportResponse> exportEncounterEverything(
            @PathVariable("id") UUID encounterId,
            @RequestHeader(value = "X-Actor-Id", required = false) String actorId,
            @RequestHeader(value = "X-Break-The-Glass-Token", required = false) String btgHeader) {
        return ResponseEntity.ok(portabilityService.exportEncounterEverything(encounterId, actorId, btgHeader));
    }

    @GetMapping(value = "/Patient/{id}/compartment/{resourceType}", produces = {MediaType.APPLICATION_JSON_VALUE, FHIR_JSON})
    @Operation(summary = "Search a specific HL7 FHIR R4 Patient Compartment (e.g., Observation, Encounter, Consent)")
    public ResponseEntity<ChartEverythingExportResponse> searchPatientCompartment(
            @PathVariable("id") UUID patientId,
            @PathVariable("resourceType") String resourceType,
            @RequestHeader(value = "X-Actor-Id", required = false) String actorId,
            @RequestHeader(value = "X-Break-The-Glass-Token", required = false) String btgHeader) {
        return ResponseEntity.ok(portabilityService.searchPatientCompartment(patientId, resourceType, actorId, btgHeader));
    }
}
