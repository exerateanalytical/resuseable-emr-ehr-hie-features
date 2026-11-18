/*
 * =============================================================================
 * Opes HealthCore SDK — Server Module FHIR R4 Practitioner CRUD Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.quickstart.service.PractitionerService;
import com.opeshealth.healthcore.quickstart.service.PractitionerService.PractitionerSearchCriteria;
import com.opeshealth.healthcore.security.tenant.FacilityTenantInterceptor;
import com.opeshealth.healthcore.service.PatientService.StringSearchFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Practitioner;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server-module REST controller exposing HL7 FHIR R4 {@code Practitioner} search,
 * retrieval, creation, optimistic-locked updates, and deactivation endpoints
 * backed by {@link PractitionerService} and audited by {@link PatientAuditAspect}.
 */
@RestController
@RequestMapping("/api/v1/practitioners")
@Tag(name = "FHIR R4 Practitioner", description = "Search, retrieve, create, update, and deactivate HL7 FHIR R4 Practitioner resources")
public class PractitionerController {

    private static final MediaType FHIR_JSON = MediaType.parseMediaType("application/fhir+json");
    private final PractitionerService practitionerService;

    public PractitionerController(PractitionerService practitionerService) {
        this.practitionerService = practitionerService;
    }

    @GetMapping(produces = {"application/fhir+json", "application/json"})
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_staff:onboard') or hasAuthority('PERM_facility:manage')")
    @Operation(
            summary = "Search HL7 FHIR R4 Practitioner resources with :contains and :exact name modifiers",
            description = "Executes a FHIR R4 Practitioner directory search returning a Bundle of type 'searchset'."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "FHIR R4 searchset Bundle of matching Practitioner resources",
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirBundleResource"))),
            @ApiResponse(responseCode = "400", description = "Invalid search filter parameter",
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirOperationOutcome"))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer JWT",
                    content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
            @ApiResponse(responseCode = "403", description = "Insufficient permission to read Practitioner directory",
                    content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
            @ApiResponse(responseCode = "429", description = "Bucket4j FHIR API rate limit exceeded",
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirOperationOutcome")))
    })
    public ResponseEntity<String> searchPractitioners(
            @Parameter(description = "Exact Practitioner resource UUID (_id)") @RequestParam(name = "_id", required = false) UUID id,
            @Parameter(description = "License or practitioner identifier token (system|value or value)") @RequestParam(required = false) String identifier,
            @Parameter(description = "Prefix match on Practitioner name or identifier") @RequestParam(name = "name", required = false) String name,
            @Parameter(description = "Case-insensitive substring match on Practitioner name") @RequestParam(name = "name:contains", required = false) String nameContains,
            @Parameter(description = "Case-sensitive exact match on Practitioner name") @RequestParam(name = "name:exact", required = false) String nameExact,
            @Parameter(description = "Prefix match on family name") @RequestParam(name = "family", required = false) String family,
            @Parameter(description = "Case-insensitive substring match on family name") @RequestParam(name = "family:contains", required = false) String familyContains,
            @Parameter(description = "Case-sensitive exact match on family name") @RequestParam(name = "family:exact", required = false) String familyExact,
            @Parameter(description = "Prefix match on given name") @RequestParam(name = "given", required = false) String given,
            @Parameter(description = "Case-insensitive substring match on given name") @RequestParam(name = "given:contains", required = false) String givenContains,
            @Parameter(description = "Case-sensitive exact match on given name") @RequestParam(name = "given:exact", required = false) String givenExact,
            @Parameter(description = "Administrative gender (male | female | other | unknown)") @RequestParam(required = false) String gender,
            @Parameter(description = "Qualification code or specialty display filter") @RequestParam(required = false) String qualification,
            @Parameter(description = "Active status filter") @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) UUID facilityId,
            @RequestHeader(value = "X-Facility-Id", required = false) UUID headerFacilityId,
            @Parameter(description = "Page size (1..100)") @RequestParam(name = "_count", defaultValue = "20") int count,
            @Parameter(description = "Zero-based result offset") @RequestParam(name = "_offset", defaultValue = "0") int offset) {
        UUID scopedFacility = facilityId != null
                ? facilityId
                : (headerFacilityId != null ? headerFacilityId : FacilityTenantInterceptor.currentFacility());

        PractitionerSearchCriteria criteria = new PractitionerSearchCriteria(
                id,
                scopedFacility,
                identifier,
                collectStringFilters(name, nameContains, nameExact),
                collectStringFilters(family, familyContains, familyExact),
                collectStringFilters(given, givenContains, givenExact),
                gender,
                qualification,
                active,
                offset,
                count
        );
        Bundle bundle = practitionerService.searchFhirBundle(criteria);
        return ResponseEntity.ok()
                .contentType(FHIR_JSON)
                .body(practitionerService.encodeFhirResource(bundle));
    }

    @GetMapping(value = "/{id}", produces = {"application/fhir+json", "application/json"})
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_staff:onboard') or hasAuthority('PERM_facility:manage')")
    @Operation(
            summary = "Retrieve a single HL7 FHIR R4 Practitioner resource by UUID with ETag",
            description = "Fetches a FHIR R4 Practitioner resource by logical UUID with weak ETag header."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "FHIR R4 Practitioner resource retrieved",
                    headers = @Header(name = "ETag", description = "Weak entity tag W/\"<version>\" for optimistic concurrency"),
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirPractitionerResource"))),
            @ApiResponse(responseCode = "404", description = "Practitioner resource not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
            @ApiResponse(responseCode = "429", description = "Bucket4j FHIR API rate limit exceeded",
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirOperationOutcome")))
    })
    public ResponseEntity<String> getPractitionerById(@PathVariable UUID id) {
        Practitioner fhirPractitioner = practitionerService.getFhirPractitionerById(id);
        String version = fhirPractitioner.hasMeta() && fhirPractitioner.getMeta().hasVersionId()
                ? fhirPractitioner.getMeta().getVersionId()
                : "0";
        return ResponseEntity.ok()
                .contentType(FHIR_JSON)
                .eTag("W/\"" + version + "\"")
                .body(practitionerService.encodeFhirResource(fhirPractitioner));
    }

    @PostMapping(consumes = {"application/fhir+json", "application/json"}, produces = {"application/fhir+json", "application/json"})
    @PreAuthorize("hasAuthority('PERM_staff:onboard') or hasAuthority('PERM_facility:manage')")
    @Operation(
            summary = "Validate and onboard an HL7 FHIR R4 Practitioner resource",
            description = "Validates and persists a new FHIR R4 Practitioner resource including license identifiers and clinical qualifications."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "FHIR R4 Practitioner created successfully",
                    headers = {
                            @Header(name = "Location", description = "Canonical URI of the newly created Practitioner resource"),
                            @Header(name = "ETag", description = "Initial weak entity tag W/\"0\"")
                    },
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirPractitionerResource"))),
            @ApiResponse(responseCode = "400", description = "Invalid FHIR R4 Practitioner payload",
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirOperationOutcome"))),
            @ApiResponse(responseCode = "409", description = "Duplicate Practitioner license identifier within the facility",
                    content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
            @ApiResponse(responseCode = "429", description = "Bucket4j FHIR API rate limit exceeded",
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirOperationOutcome")))
    })
    public ResponseEntity<String> createPractitioner(
            @RequestParam(required = false) UUID facilityId,
            @RequestHeader(value = "X-Facility-Id", required = false) UUID headerFacilityId,
            @RequestBody String fhirPractitionerJson) {
        UUID scopedFacility = facilityId != null
                ? facilityId
                : (headerFacilityId != null ? headerFacilityId : FacilityTenantInterceptor.currentFacility());
        Practitioner created = practitionerService.createFromFhirJson(scopedFacility, fhirPractitionerJson);
        String version = created.hasMeta() && created.getMeta().hasVersionId()
                ? created.getMeta().getVersionId()
                : "0";
        return ResponseEntity.created(URI.create("/api/v1/practitioners/" + created.getIdElement().getIdPart()))
                .contentType(FHIR_JSON)
                .eTag("W/\"" + version + "\"")
                .body(practitionerService.encodeFhirResource(created));
    }

    @PutMapping(value = "/{id}", consumes = {"application/fhir+json", "application/json"}, produces = {"application/fhir+json", "application/json"})
    @PreAuthorize("hasAuthority('PERM_staff:onboard') or hasAuthority('PERM_facility:manage')")
    @Operation(
            summary = "Validate and update an HL7 FHIR R4 Practitioner resource with If-Match ETag check",
            description = "Updates an existing FHIR R4 Practitioner resource with optimistic locking via If-Match."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "FHIR R4 Practitioner updated successfully",
                    headers = @Header(name = "ETag", description = "Updated weak entity tag W/\"<version>\""),
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirPractitionerResource"))),
            @ApiResponse(responseCode = "400", description = "Invalid FHIR R4 Practitioner payload",
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirOperationOutcome"))),
            @ApiResponse(responseCode = "404", description = "Practitioner resource not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
            @ApiResponse(responseCode = "412", description = "Precondition Failed — If-Match ETag mismatch",
                    content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
            @ApiResponse(responseCode = "429", description = "Bucket4j FHIR API rate limit exceeded",
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirOperationOutcome")))
    })
    public ResponseEntity<String> updatePractitioner(
            @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody String fhirPractitionerJson) {
        Practitioner updated = practitionerService.updateFromFhirJson(id, fhirPractitionerJson, ifMatch);
        String version = updated.hasMeta() && updated.getMeta().hasVersionId()
                ? updated.getMeta().getVersionId()
                : "0";
        return ResponseEntity.ok()
                .contentType(FHIR_JSON)
                .eTag("W/\"" + version + "\"")
                .body(practitionerService.encodeFhirResource(updated));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_staff:onboard') or hasAuthority('PERM_facility:manage')")
    @Operation(
            summary = "Deactivate (soft-delete) a Practitioner resource",
            description = "Marks the Practitioner record as inactive while retaining historical clinical attribution."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Practitioner resource deactivated"),
            @ApiResponse(responseCode = "404", description = "Practitioner resource not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
            @ApiResponse(responseCode = "429", description = "Bucket4j FHIR API rate limit exceeded",
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirOperationOutcome")))
    })
    public ResponseEntity<Void> deactivatePractitioner(@PathVariable UUID id) {
        practitionerService.deactivatePractitioner(id);
        return ResponseEntity.noContent().build();
    }

    private static List<StringSearchFilter> collectStringFilters(String prefixVal, String containsVal, String exactVal) {
        List<StringSearchFilter> filters = new ArrayList<>();
        if (prefixVal != null && !prefixVal.isBlank()) {
            filters.add(StringSearchFilter.prefix(prefixVal));
        }
        if (containsVal != null && !containsVal.isBlank()) {
            filters.add(StringSearchFilter.contains(containsVal));
        }
        if (exactVal != null && !exactVal.isBlank()) {
            filters.add(StringSearchFilter.exact(exactVal));
        }
        return filters;
    }
}
