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
@Tag(name = "FHIR Practitioner API", description = "Search, retrieve, create, update, and deactivate HL7 FHIR R4 Practitioner resources")
public class PractitionerController {

    private static final MediaType FHIR_JSON = MediaType.parseMediaType("application/fhir+json");
    private final PractitionerService practitionerService;

    public PractitionerController(PractitionerService practitionerService) {
        this.practitionerService = practitionerService;
    }

    @GetMapping(produces = {"application/fhir+json", "application/json"})
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_staff:onboard') or hasAuthority('PERM_facility:manage')")
    @Operation(summary = "Search HL7 FHIR R4 Practitioner resources with :contains and :exact name modifiers")
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
    @Operation(summary = "Retrieve a single HL7 FHIR R4 Practitioner resource by UUID with ETag")
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
    @Operation(summary = "Validate and onboard an HL7 FHIR R4 Practitioner resource")
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
    @Operation(summary = "Validate and update an HL7 FHIR R4 Practitioner resource with If-Match ETag check")
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
    @Operation(summary = "Deactivate (soft-delete) a Practitioner resource")
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
