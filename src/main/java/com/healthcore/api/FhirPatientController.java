package com.healthcore.api;

import com.healthcore.fhir.patient.FhirPatientService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/fhir/r4/Patient")
public class FhirPatientController {

    public static final String FHIR_JSON_VALUE = "application/fhir+json";
    private static final MediaType FHIR_JSON = MediaType.parseMediaType(FHIR_JSON_VALUE);

    private final FhirPatientService fhirPatientService;

    public FhirPatientController(FhirPatientService fhirPatientService) {
        this.fhirPatientService = fhirPatientService;
    }

    @PostMapping(
            consumes = {FHIR_JSON_VALUE, MediaType.APPLICATION_JSON_VALUE},
            produces = {FHIR_JSON_VALUE, MediaType.APPLICATION_JSON_VALUE}
    )
    @PreAuthorize("hasAnyRole('CLINICIAN', 'REGISTRAR', 'ADMIN')")
    public ResponseEntity<String> create(@RequestBody String fhirPatientJson) {
        var result = fhirPatientService.createPatient(fhirPatientJson);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(result.id())
                .toUri();
        return ResponseEntity.created(location)
                .contentType(FHIR_JSON)
                .body(result.fhirJson());
    }

    @GetMapping(
            value = "/{id}",
            produces = {FHIR_JSON_VALUE, MediaType.APPLICATION_JSON_VALUE}
    )
    @PreAuthorize("hasAnyRole('CLINICIAN', 'REGISTRAR', 'NURSE', 'ADMIN')")
    public ResponseEntity<String> read(@PathVariable UUID id) {
        return ResponseEntity.ok()
                .contentType(FHIR_JSON)
                .body(fhirPatientService.getPatientById(id));
    }

    @PutMapping(
            value = "/{id}",
            consumes = {FHIR_JSON_VALUE, MediaType.APPLICATION_JSON_VALUE},
            produces = {FHIR_JSON_VALUE, MediaType.APPLICATION_JSON_VALUE}
    )
    @PreAuthorize("hasAnyRole('CLINICIAN', 'REGISTRAR', 'ADMIN')")
    public ResponseEntity<String> update(@PathVariable UUID id, @RequestBody String fhirPatientJson) {
        return ResponseEntity.ok()
                .contentType(FHIR_JSON)
                .body(fhirPatientService.updatePatient(id, fhirPatientJson));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID id) {
        fhirPatientService.deactivatePatient(id);
    }

    @GetMapping(produces = {FHIR_JSON_VALUE, MediaType.APPLICATION_JSON_VALUE})
    @PreAuthorize("hasAnyRole('CLINICIAN', 'REGISTRAR', 'NURSE', 'ADMIN')")
    public ResponseEntity<String> search(
            @RequestParam(required = false) String family,
            @RequestParam(required = false) String given,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate birthdate,
            @RequestParam(required = false) String gender,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false, name = "address-city") String city,
            @RequestParam(required = false) String identifier,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        String bundleJson = fhirPatientService.searchPatients(
                family, given, birthdate, gender, phone, city, identifier, active, page, size);
        return ResponseEntity.ok()
                .contentType(FHIR_JSON)
                .body(bundleJson);
    }
}
