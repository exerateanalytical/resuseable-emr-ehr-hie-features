/*
 * =============================================================================
 * Opes HealthCore SDK — Server Module FHIR R4 Observation CRUD Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.quickstart.service.CdsHooksService;
import com.opeshealth.healthcore.quickstart.service.CdsHooksService.CdsHookResponse;
import com.opeshealth.healthcore.quickstart.service.ObservationService;
import com.opeshealth.healthcore.quickstart.service.ObservationService.ObservationSearchCriteria;
import com.opeshealth.healthcore.security.tenant.FacilityTenantInterceptor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Observation;
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
 * Server-module REST controller exposing HL7 FHIR R4 {@code Observation} search,
 * retrieval, creation, optimistic-locked updates, and entered-in-error retraction
 * endpoints backed by {@link ObservationService}, audited by {@link PatientAuditAspect},
 * and triggering CDS Hooks 2.0 alerts on critical clinical values.
 */
@RestController
@RequestMapping("/api/v1/observations")
@Tag(name = "FHIR R4 Observation", description = "Search, retrieve, create, update, and mark entered-in-error HL7 FHIR R4 Observation resources")
public class ObservationController {

    private static final MediaType FHIR_JSON = MediaType.parseMediaType("application/fhir+json");
    private final ObservationService observationService;
    private final CdsHooksService cdsHooksService;

    public ObservationController(ObservationService observationService) {
        this(observationService, (CdsHooksService) null);
    }

    @Autowired
    public ObservationController(
            ObservationService observationService,
            ObjectProvider<CdsHooksService> cdsHooksServiceProvider) {
        this(observationService, cdsHooksServiceProvider != null ? cdsHooksServiceProvider.getIfAvailable() : null);
    }

    public ObservationController(ObservationService observationService, CdsHooksService cdsHooksService) {
        this.observationService = observationService;
        this.cdsHooksService = cdsHooksService;
    }

    @GetMapping(produces = {"application/fhir+json", "application/json"})
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_lab:result:enter') or hasAuthority('PERM_lab:verify')")
    @Operation(
            summary = "Search HL7 FHIR R4 Observation resources by patient, encounter, LOINC code, category, status, and date ranges",
            description = "Executes a FHIR R4 Observation query returning a Bundle of type 'searchset'."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "FHIR R4 searchset Bundle of matching Observation resources",
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirBundleResource"))),
            @ApiResponse(responseCode = "400", description = "Invalid search parameter or date range expression",
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirOperationOutcome"))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer JWT",
                    content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
            @ApiResponse(responseCode = "403", description = "Insufficient permission to read clinical observations",
                    content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
            @ApiResponse(responseCode = "429", description = "Bucket4j FHIR API rate limit exceeded",
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirOperationOutcome")))
    })
    public ResponseEntity<String> searchObservations(
            @Parameter(description = "Exact Observation resource UUID (_id)") @RequestParam(name = "_id", required = false) UUID id,
            @Parameter(description = "Observation identifier token (system|value or value)") @RequestParam(required = false) String identifier,
            @Parameter(description = "Patient UUID or reference (Patient/<uuid>)") @RequestParam(required = false) String patient,
            @Parameter(description = "Subject reference alias (Patient/<uuid>)") @RequestParam(required = false) String subject,
            @Parameter(description = "Encounter UUID or reference (Encounter/<uuid>)") @RequestParam(required = false) String encounter,
            @Parameter(description = "Performer Practitioner UUID or reference") @RequestParam(required = false) String performer,
            @Parameter(description = "Observation category token (e.g., vital-signs, laboratory)") @RequestParam(required = false) String category,
            @Parameter(description = "LOINC/SNOMED code token (system|code or code)") @RequestParam(required = false) String code,
            @Parameter(description = "Case-insensitive substring match against Observation code display/text") @RequestParam(name = "code:text", required = false) String codeText,
            @Parameter(description = "Observation status (registered | preliminary | final | amended | corrected | cancelled | entered-in-error | unknown)") @RequestParam(required = false) String status,
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

        ObservationSearchCriteria criteria = new ObservationSearchCriteria(
                id,
                scopedFacility,
                identifier,
                resolvedPatient,
                encounter,
                performer,
                category,
                code,
                codeText,
                status,
                date,
                dateFrom,
                dateTo,
                offset,
                count
        );
        Bundle bundle = observationService.searchFhirBundle(criteria);
        return ResponseEntity.ok()
                .contentType(FHIR_JSON)
                .body(observationService.encodeFhirResource(bundle));
    }

    @GetMapping(value = "/{id}", produces = {"application/fhir+json", "application/json"})
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_lab:result:enter') or hasAuthority('PERM_lab:verify')")
    @Operation(
            summary = "Retrieve a single HL7 FHIR R4 Observation resource by UUID with ETag",
            description = "Fetches a FHIR R4 Observation resource by UUID and includes weak ETag version metadata."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "FHIR R4 Observation resource retrieved",
                    headers = @Header(name = "ETag", description = "Weak entity tag W/\"<version>\" for optimistic concurrency"),
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirObservationResource"))),
            @ApiResponse(responseCode = "404", description = "Observation resource not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
            @ApiResponse(responseCode = "429", description = "Bucket4j FHIR API rate limit exceeded",
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirOperationOutcome")))
    })
    public ResponseEntity<String> getObservationById(@PathVariable UUID id) {
        Observation fhirObservation = observationService.getFhirObservationById(id);
        String version = fhirObservation.hasMeta() && fhirObservation.getMeta().hasVersionId()
                ? fhirObservation.getMeta().getVersionId()
                : "0";
        return ResponseEntity.ok()
                .contentType(FHIR_JSON)
                .eTag("W/\"" + version + "\"")
                .body(observationService.encodeFhirResource(fhirObservation));
    }

    @PostMapping(consumes = {"application/fhir+json", "application/json"}, produces = {"application/fhir+json", "application/json"})
    @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_vitals:write') or hasAuthority('PERM_lab:result:enter') or hasAuthority('PERM_lab:verify')")
    @Operation(
            summary = "Validate and record an HL7 FHIR R4 Observation resource",
            description = "Validates the FHIR R4 Observation (vital signs or laboratory result) via HAPI FHIR Validator and evaluates CDS Hooks 2.0 critical value thresholds."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "FHIR R4 Observation created successfully",
                    headers = {
                            @Header(name = "Location", description = "Canonical URI of the newly created Observation resource"),
                            @Header(name = "ETag", description = "Initial weak entity tag W/\"0\""),
                            @Header(name = "X-CDS-Hooks-Alert-Count", description = "Number of CDS Hooks 2.0 clinical alert cards triggered"),
                            @Header(name = "X-CDS-Hooks-Max-Indicator", description = "Highest CDS Hooks 2.0 severity indicator (info | warning | critical)")
                    },
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirObservationResource"))),
            @ApiResponse(responseCode = "400", description = "HAPI FHIR R4 Observation validation failure (missing subject, invalid LOINC/UCUM quantity, etc.)",
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirOperationOutcome"))),
            @ApiResponse(responseCode = "429", description = "Bucket4j FHIR API rate limit exceeded",
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirOperationOutcome")))
    })
    public ResponseEntity<String> createObservation(
            @RequestParam(required = false) UUID facilityId,
            @RequestHeader(value = "X-Facility-Id", required = false) UUID headerFacilityId,
            @RequestBody String fhirObservationJson) {
        UUID scopedFacility = facilityId != null
                ? facilityId
                : (headerFacilityId != null ? headerFacilityId : FacilityTenantInterceptor.currentFacility());
        Observation created = observationService.createFromFhirJson(scopedFacility, fhirObservationJson);
        String version = created.hasMeta() && created.getMeta().hasVersionId()
                ? created.getMeta().getVersionId()
                : "0";
        ResponseEntity.BodyBuilder builder = ResponseEntity
                .created(URI.create("/api/v1/observations/" + created.getIdElement().getIdPart()))
                .contentType(FHIR_JSON)
                .eTag("W/\"" + version + "\"");
        if (cdsHooksService != null) {
            CdsHookResponse cds = cdsHooksService.triggerObservationInteraction(created, scopedFacility);
            if (!cds.cards().isEmpty()) {
                builder.header("X-CDS-Hooks-Alert-Count", Integer.toString(cds.cards().size()));
                builder.header("X-CDS-Hooks-Max-Indicator", cds.highestIndicator());
            }
        }
        return builder.body(observationService.encodeFhirResource(created));
    }

    @PutMapping(value = "/{id}", consumes = {"application/fhir+json", "application/json"}, produces = {"application/fhir+json", "application/json"})
    @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_vitals:write') or hasAuthority('PERM_lab:result:enter') or hasAuthority('PERM_lab:verify')")
    @Operation(
            summary = "Validate and update an HL7 FHIR R4 Observation resource with If-Match ETag check",
            description = "Validates and updates an existing FHIR R4 Observation resource with optimistic locking and CDS Hooks 2.0 re-evaluation."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "FHIR R4 Observation updated successfully",
                    headers = @Header(name = "ETag", description = "Updated weak entity tag W/\"<version>\""),
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirObservationResource"))),
            @ApiResponse(responseCode = "400", description = "HAPI FHIR R4 Observation validation failure",
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirOperationOutcome"))),
            @ApiResponse(responseCode = "404", description = "Observation resource not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
            @ApiResponse(responseCode = "412", description = "Precondition Failed — If-Match ETag mismatch",
                    content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
            @ApiResponse(responseCode = "429", description = "Bucket4j FHIR API rate limit exceeded",
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirOperationOutcome")))
    })
    public ResponseEntity<String> updateObservation(
            @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody String fhirObservationJson) {
        Observation updated = observationService.updateFromFhirJson(id, fhirObservationJson, ifMatch);
        String version = updated.hasMeta() && updated.getMeta().hasVersionId()
                ? updated.getMeta().getVersionId()
                : "0";
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(FHIR_JSON)
                .eTag("W/\"" + version + "\"");
        if (cdsHooksService != null) {
            CdsHookResponse cds = cdsHooksService.triggerObservationInteraction(updated, FacilityTenantInterceptor.currentFacility());
            if (!cds.cards().isEmpty()) {
                builder.header("X-CDS-Hooks-Alert-Count", Integer.toString(cds.cards().size()));
                builder.header("X-CDS-Hooks-Max-Indicator", cds.highestIndicator());
            }
        }
        return builder.body(observationService.encodeFhirResource(updated));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_lab:verify') or hasAuthority('PERM_facility:manage')")
    @Operation(
            summary = "Mark an Observation resource as entered-in-error (clinical soft delete)",
            description = "Transitions the Observation status to 'entered-in-error' in compliance with HL7 FHIR clinical immutability rules."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Observation marked as entered-in-error"),
            @ApiResponse(responseCode = "404", description = "Observation resource not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
            @ApiResponse(responseCode = "429", description = "Bucket4j FHIR API rate limit exceeded",
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirOperationOutcome")))
    })
    public ResponseEntity<Void> deactivateObservation(@PathVariable UUID id) {
        observationService.markEnteredInError(id);
        return ResponseEntity.noContent().build();
    }
}
