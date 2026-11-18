/*
 * =============================================================================
 * Opes HealthCore SDK — Server Module FHIR R4 Encounter CRUD Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.quickstart.service.CdsHooksService;
import com.opeshealth.healthcore.quickstart.service.CdsHooksService.CdsHookResponse;
import com.opeshealth.healthcore.quickstart.service.EncounterService;
import com.opeshealth.healthcore.quickstart.service.EncounterService.EncounterSearchCriteria;
import com.opeshealth.healthcore.security.tenant.FacilityTenantInterceptor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Encounter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Server-module REST controller exposing HL7 FHIR R4 {@code Encounter} search,
 * retrieval, creation, optimistic-locked updates, and cancellation endpoints
 * backed by {@link EncounterService}, audited by {@link PatientAuditAspect},
 * and optionally triggering CDS Hooks 2.0 encounter evaluations.
 */
@RestController
@RequestMapping("/api/v1/encounters")
@Tag(name = "FHIR Encounter API", description = "Search, retrieve, create, update, and cancel HL7 FHIR R4 Encounter resources")
public class EncounterController {

    private static final MediaType FHIR_JSON = MediaType.parseMediaType("application/fhir+json");
    private final EncounterService encounterService;
    private final CdsHooksService cdsHooksService;

    public EncounterController(EncounterService encounterService) {
        this(encounterService, (CdsHooksService) null);
    }

    @Autowired
    public EncounterController(
            EncounterService encounterService,
            ObjectProvider<CdsHooksService> cdsHooksServiceProvider) {
        this(encounterService, cdsHooksServiceProvider != null ? cdsHooksServiceProvider.getIfAvailable() : null);
    }

    public EncounterController(EncounterService encounterService, CdsHooksService cdsHooksService) {
        this.encounterService = encounterService;
        this.cdsHooksService = cdsHooksService;
    }

    @GetMapping(produces = {"application/fhir+json", "application/json"})
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_encounter:draft') or hasAuthority('PERM_encounter:sign')")
    @Operation(summary = "Search HL7 FHIR R4 Encounter resources by patient, status, class, practitioner, location, and date ranges")
    public ResponseEntity<String> searchEncounters(
            @Parameter(description = "Exact Encounter resource UUID (_id)") @RequestParam(name = "_id", required = false) UUID id,
            @Parameter(description = "Patient UUID or reference (Patient/<uuid>)") @RequestParam(required = false) String patient,
            @Parameter(description = "Subject reference alias (Patient/<uuid>)") @RequestParam(required = false) String subject,
            @Parameter(description = "Encounter status (planned | arrived | triaged | in-progress | finished | cancelled)") @RequestParam(required = false) String status,
            @Parameter(description = "Encounter class code (AMB | EMER | IMP | HH)") @RequestParam(name = "class", required = false) String encounterClass,
            @Parameter(description = "Attending Practitioner ID or reference") @RequestParam(required = false) String practitioner,
            @Parameter(description = "Department/Location code") @RequestParam(required = false) String location,
            @Parameter(description = "Primary ICD-10 reason code") @RequestParam(name = "reason-code", required = false) String reasonCode,
            @Parameter(description = "One or more FHIR date expressions (eq/ne/gt/lt/ge/le or partial dates)") @RequestParam(name = "date", required = false) List<String> date,
            @Parameter(description = "Inclusive lower date bound (YYYY-MM-DD)") @RequestParam(required = false) LocalDate dateFrom,
            @Parameter(description = "Inclusive upper date bound (YYYY-MM-DD)") @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) UUID facilityId,
            @RequestHeader(value = "X-Facility-Id", required = false) UUID headerFacilityId,
            @Parameter(description = "Page size (1..100)") @RequestParam(name = "_count", defaultValue = "20") int count,
            @Parameter(description = "Zero-based result offset") @RequestParam(name = "_offset", defaultValue = "0") int offset) {
        UUID scopedFacility = facilityId != null
                ? facilityId
                : (headerFacilityId != null ? headerFacilityId : FacilityTenantInterceptor.currentFacility());
        String resolvedPatient = patient != null && !patient.isBlank() ? patient : subject;

        EncounterSearchCriteria criteria = new EncounterSearchCriteria(
                id,
                scopedFacility,
                resolvedPatient,
                status,
                encounterClass,
                practitioner,
                location,
                reasonCode,
                date,
                dateFrom,
                dateTo,
                offset,
                count
        );
        Bundle bundle = encounterService.searchFhirBundle(criteria);
        return ResponseEntity.ok()
                .contentType(FHIR_JSON)
                .body(encounterService.encodeFhirResource(bundle));
    }

    @GetMapping(value = "/{id}", produces = {"application/fhir+json", "application/json"})
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_encounter:draft') or hasAuthority('PERM_encounter:sign')")
    @Operation(summary = "Retrieve a single HL7 FHIR R4 Encounter resource by UUID with ETag")
    public ResponseEntity<String> getEncounterById(@PathVariable UUID id) {
        Encounter fhirEncounter = encounterService.getFhirEncounterById(id);
        String version = fhirEncounter.hasMeta() && fhirEncounter.getMeta().hasVersionId()
                ? fhirEncounter.getMeta().getVersionId()
                : "0";
        return ResponseEntity.ok()
                .contentType(FHIR_JSON)
                .eTag("W/\"" + version + "\"")
                .body(encounterService.encodeFhirResource(fhirEncounter));
    }

    @PostMapping(consumes = {"application/fhir+json", "application/json"}, produces = {"application/fhir+json", "application/json"})
    @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:draft') or hasAuthority('PERM_encounter:sign')")
    @Operation(summary = "Validate and create an HL7 FHIR R4 Encounter resource")
    public ResponseEntity<String> createEncounter(
            @RequestParam(required = false) UUID facilityId,
            @RequestHeader(value = "X-Facility-Id", required = false) UUID headerFacilityId,
            @RequestBody String fhirEncounterJson) {
        UUID scopedFacility = facilityId != null
                ? facilityId
                : (headerFacilityId != null ? headerFacilityId : FacilityTenantInterceptor.currentFacility());
        Encounter created = encounterService.createFromFhirJson(scopedFacility, fhirEncounterJson);
        String version = created.hasMeta() && created.getMeta().hasVersionId()
                ? created.getMeta().getVersionId()
                : "0";

        ResponseEntity.BodyBuilder builder = ResponseEntity
                .created(URI.create("/api/v1/encounters/" + created.getIdElement().getIdPart()))
                .contentType(FHIR_JSON)
                .eTag("W/\"" + version + "\"");

        if (cdsHooksService != null) {
            CdsHookResponse cds = cdsHooksService.triggerEncounterInteraction(created, scopedFacility);
            if (!cds.cards().isEmpty()) {
                builder.header("X-CDS-Hooks-Alert-Count", Integer.toString(cds.cards().size()));
                builder.header("X-CDS-Hooks-Max-Indicator", cds.highestIndicator());
            }
        }
        return builder.body(encounterService.encodeFhirResource(created));
    }

    @PutMapping(value = "/{id}", consumes = {"application/fhir+json", "application/json"}, produces = {"application/fhir+json", "application/json"})
    @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:draft') or hasAuthority('PERM_encounter:sign')")
    @Operation(summary = "Validate and update an HL7 FHIR R4 Encounter resource with If-Match ETag check")
    public ResponseEntity<String> updateEncounter(
            @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody String fhirEncounterJson) {
        Encounter updated = encounterService.updateFromFhirJson(id, fhirEncounterJson, ifMatch);
        String version = updated.hasMeta() && updated.getMeta().hasVersionId()
                ? updated.getMeta().getVersionId()
                : "0";
        return ResponseEntity.ok()
                .contentType(FHIR_JSON)
                .eTag("W/\"" + version + "\"")
                .body(encounterService.encodeFhirResource(updated));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_facility:manage')")
    @Operation(summary = "Cancel (soft-delete) an Encounter resource")
    public ResponseEntity<Void> deactivateEncounter(@PathVariable UUID id) {
        encounterService.cancelEncounter(id);
        return ResponseEntity.noContent().build();
    }
}
