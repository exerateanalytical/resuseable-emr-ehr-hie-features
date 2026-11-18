/*
 * =============================================================================
 * Opes HealthCore SDK — Server Module FHIR R4 Patient Search & CRUD Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.quickstart.service.CdsHooksService;
import com.opeshealth.healthcore.quickstart.service.CdsHooksService.CdsHookResponse;
import com.opeshealth.healthcore.security.tenant.FacilityTenantInterceptor;
import com.opeshealth.healthcore.service.PatientService;
import com.opeshealth.healthcore.service.PatientService.PatientSearchCriteria;
import com.opeshealth.healthcore.service.PatientService.StringSearchFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server-module REST controller exposing HL7 FHIR R4 {@code Patient} search,
 * retrieval, creation, optimistic-locked updates, and deactivation endpoints
 * backed by {@link PatientService} and triggering CDS Hooks 2.0 on patient interactions.
 */
@RestController
@RequestMapping("/api/v1/patients")
@Tag(name = "FHIR Patient API", description = "Search, retrieve, create, update, and deactivate HL7 FHIR R4 Patient resources")
public class PatientController {

    private static final MediaType FHIR_JSON = MediaType.parseMediaType("application/fhir+json");
    private final PatientService patientService;
    private final CdsHooksService cdsHooksService;

    public PatientController(PatientService patientService) {
        this(patientService, (CdsHooksService) null);
    }

    @Autowired
    public PatientController(
            PatientService patientService,
            ObjectProvider<CdsHooksService> cdsHooksServiceProvider) {
        this(patientService, cdsHooksServiceProvider != null ? cdsHooksServiceProvider.getIfAvailable() : null);
    }

    public PatientController(PatientService patientService, CdsHooksService cdsHooksService) {
        this.patientService = patientService;
        this.cdsHooksService = cdsHooksService;
    }

    @GetMapping(produces = {"application/fhir+json", "application/json"})
    @PreAuthorize("hasAuthority('PERM_patient:read')")
    @Operation(summary = "Search HL7 FHIR R4 Patient resources supporting :contains, :exact modifiers and birthdate ranges")
    public ResponseEntity<String> searchPatients(
            @Parameter(description = "Exact Patient resource UUID (_id)") @RequestParam(name = "_id", required = false) UUID id,
            @Parameter(description = "Identifier token (system|value or value)") @RequestParam(required = false) String identifier,
            @Parameter(description = "Prefix match on Patient name or MRN (FHIR default)") @RequestParam(name = "name", required = false) String name,
            @Parameter(description = "Case-insensitive substring match on Patient name or MRN") @RequestParam(name = "name:contains", required = false) String nameContains,
            @Parameter(description = "Case-sensitive exact match on Patient name or MRN") @RequestParam(name = "name:exact", required = false) String nameExact,
            @Parameter(description = "Prefix match on family name") @RequestParam(name = "family", required = false) String family,
            @Parameter(description = "Case-insensitive substring match on family name") @RequestParam(name = "family:contains", required = false) String familyContains,
            @Parameter(description = "Case-sensitive exact match on family name") @RequestParam(name = "family:exact", required = false) String familyExact,
            @Parameter(description = "Prefix match on given/middle name") @RequestParam(name = "given", required = false) String given,
            @Parameter(description = "Case-insensitive substring match on given/middle name") @RequestParam(name = "given:contains", required = false) String givenContains,
            @Parameter(description = "Case-sensitive exact match on given/middle name") @RequestParam(name = "given:exact", required = false) String givenExact,
            @Parameter(description = "One or more FHIR birthdate expressions with eq/ne/gt/lt/ge/le/sa/eb/ap prefixes or partial dates") @RequestParam(name = "birthdate", required = false) List<String> birthdate,
            @Parameter(description = "Inclusive lower bound for birthDate range filtering (YYYY-MM-DD)") @RequestParam(required = false) LocalDate birthdateFrom,
            @Parameter(description = "Inclusive upper bound for birthDate range filtering (YYYY-MM-DD)") @RequestParam(required = false) LocalDate birthdateTo,
            @Parameter(description = "Administrative gender (male | female | other | unknown)") @RequestParam(required = false) String gender,
            @Parameter(description = "Primary or secondary phone number") @RequestParam(required = false) String phone,
            @Parameter(description = "Email address") @RequestParam(required = false) String email,
            @Parameter(description = "Active status filter") @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) UUID facilityId,
            @RequestHeader(value = "X-Facility-Id", required = false) UUID headerFacilityId,
            @Parameter(description = "Page size (1..100)") @RequestParam(name = "_count", defaultValue = "20") int count,
            @Parameter(description = "Zero-based result offset") @RequestParam(name = "_offset", defaultValue = "0") int offset) {
        UUID scopedFacility = facilityId != null
                ? facilityId
                : (headerFacilityId != null ? headerFacilityId : FacilityTenantInterceptor.currentFacility());

        PatientSearchCriteria criteria = new PatientSearchCriteria(
                id,
                scopedFacility,
                identifier,
                collectStringFilters(name, nameContains, nameExact),
                collectStringFilters(family, familyContains, familyExact),
                collectStringFilters(given, givenContains, givenExact),
                birthdate,
                birthdateFrom,
                birthdateTo,
                gender,
                phone,
                email,
                active,
                offset,
                count
        );
        Bundle bundle = patientService.searchFhirBundle(criteria);
        return ResponseEntity.ok()
                .contentType(FHIR_JSON)
                .body(patientService.encodeFhirResource(bundle));
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

    @GetMapping(value = "/{id}", produces = {"application/fhir+json", "application/json"})
    @PreAuthorize("hasAuthority('PERM_patient:read')")
    @Operation(summary = "Retrieve a single HL7 FHIR R4 Patient resource by UUID with ETag")
    public ResponseEntity<String> getPatientById(@PathVariable UUID id) {
        Patient fhirPatient = patientService.getFhirPatientById(id);
        String version = fhirPatient.hasMeta() && fhirPatient.getMeta().hasVersionId()
                ? fhirPatient.getMeta().getVersionId()
                : "0";
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(FHIR_JSON)
                .eTag("W/\"" + version + "\"");
        if (cdsHooksService != null) {
            CdsHookResponse cds = cdsHooksService.triggerPatientInteraction(fhirPatient, FacilityTenantInterceptor.currentFacility());
            if (!cds.cards().isEmpty()) {
                builder.header("X-CDS-Hooks-Alert-Count", Integer.toString(cds.cards().size()));
                builder.header("X-CDS-Hooks-Max-Indicator", cds.highestIndicator());
            }
        }
        return builder.body(patientService.encodeFhirResource(fhirPatient));
    }

    @PostMapping(consumes = {"application/fhir+json", "application/json"}, produces = {"application/fhir+json", "application/json"})
    @PreAuthorize("hasAuthority('PERM_patient:write')")
    @Operation(summary = "Validate and create an HL7 FHIR R4 Patient resource")
    public ResponseEntity<String> createPatient(
            @RequestParam(required = false) UUID facilityId,
            @RequestHeader(value = "X-Facility-Id", required = false) UUID headerFacilityId,
            @RequestBody String fhirPatientJson) {
        UUID scopedFacility = facilityId != null
                ? facilityId
                : (headerFacilityId != null ? headerFacilityId : FacilityTenantInterceptor.currentFacility());
        Patient created = patientService.createFromFhirJson(scopedFacility, fhirPatientJson);
        String version = created.hasMeta() && created.getMeta().hasVersionId()
                ? created.getMeta().getVersionId()
                : "0";
        ResponseEntity.BodyBuilder builder = ResponseEntity
                .created(URI.create("/api/v1/patients/" + created.getIdElement().getIdPart()))
                .contentType(FHIR_JSON)
                .eTag("W/\"" + version + "\"");
        if (cdsHooksService != null) {
            CdsHookResponse cds = cdsHooksService.triggerPatientInteraction(created, scopedFacility);
            if (!cds.cards().isEmpty()) {
                builder.header("X-CDS-Hooks-Alert-Count", Integer.toString(cds.cards().size()));
                builder.header("X-CDS-Hooks-Max-Indicator", cds.highestIndicator());
            }
        }
        return builder.body(patientService.encodeFhirResource(created));
    }

    @PutMapping(value = "/{id}", consumes = {"application/fhir+json", "application/json"}, produces = {"application/fhir+json", "application/json"})
    @PreAuthorize("hasAuthority('PERM_patient:write')")
    @Operation(summary = "Validate and update an HL7 FHIR R4 Patient resource with If-Match ETag check")
    public ResponseEntity<String> updatePatient(
            @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody String fhirPatientJson) {
        Patient updated = patientService.updateFromFhirJson(id, fhirPatientJson, ifMatch);
        String version = updated.hasMeta() && updated.getMeta().hasVersionId()
                ? updated.getMeta().getVersionId()
                : "0";
        return ResponseEntity.ok()
                .contentType(FHIR_JSON)
                .eTag("W/\"" + version + "\"")
                .body(patientService.encodeFhirResource(updated));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_facility:manage')")
    @Operation(summary = "Deactivate (soft-delete) a Patient resource")
    public ResponseEntity<Void> deactivatePatient(@PathVariable UUID id) {
        patientService.deactivatePatient(id);
        return ResponseEntity.noContent().build();
    }
}
