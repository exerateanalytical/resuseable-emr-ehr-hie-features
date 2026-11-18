/*
 * =============================================================================
 * Opes HealthCore SDK — FHIR R4 Patient Resource Mapper & REST Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.mpi.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientAggregateEntity;
import com.opeshealth.healthcore.mpi.match.FellegiSunterMatcher.MatchCandidate;
import com.opeshealth.healthcore.mpi.service.MpiCommandService;
import com.opeshealth.healthcore.mpi.service.MpiCommandService.RegisterPatientCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Exposes HL7 FHIR R4 {@code /fhir/r4/Patient} endpoints with {@code ETag} / {@code If-Match}
 * optimistic concurrency alongside probabilistic MPI matching and Golden Record merge/unmerge endpoints.
 */
@RestController
@Tag(name = "MPI & FHIR R4 Patient", description = "Master Patient Index, Fellegi-Sunter matching, and FHIR R4 Patient API")
public class FhirPatientController {

    private static final FhirContext FHIR_R4 = FhirContext.forR4();

    private final MpiCommandService mpiService;
    private final MpiCommandService.PatientRepository patientRepository;

    public FhirPatientController(
            MpiCommandService mpiService,
            MpiCommandService.PatientRepository patientRepository) {
        this.mpiService = mpiService;
        this.patientRepository = patientRepository;
    }

    @PostMapping("/api/v1/mpi/patients")
    @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_facility:manage')")
    @Operation(summary = "Register a new patient Golden Record with encrypted National ID blind index")
    public PatientAggregateEntity register(@RequestBody RegisterPatientCommand cmd) {
        return mpiService.registerPatient(cmd);
    }

    @GetMapping("/api/v1/mpi/patients/match")
    @PreAuthorize("hasAuthority('PERM_patient:read')")
    @Operation(summary = "Run probabilistic Fellegi-Sunter duplicate patient match")
    public List<MatchCandidate> match(
            @RequestParam String familyName,
            @RequestParam String givenName,
            @RequestParam java.time.LocalDate birthDate,
            @RequestParam String gender,
            @RequestParam(required = false) String phone) {
        return mpiService.findProbabilisticMatches(familyName, givenName, birthDate, gender, phone);
    }

    @GetMapping("/api/v1/mpi/patients/fuzzy-search")
    @PreAuthorize("hasAuthority('PERM_patient:read')")
    @Operation(summary = "Fuzzy patient name/MRN lookup using PostgreSQL pg_trgm GIN trigram similarity")
    public List<PatientAggregateEntity> fuzzySearch(
            @RequestParam UUID facilityId,
            @RequestParam String query,
            @RequestParam(defaultValue = "20") int limit) {
        return mpiService.findByFacilityAndFuzzyName(facilityId, query, limit);
    }

    @PostMapping("/api/v1/mpi/patients/merge")
    @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_facility:manage')")
    @Operation(summary = "Reversibly merge duplicate patient record into surviving Golden Record")
    public Object merge(
            @RequestParam UUID facilityId,
            @RequestParam UUID sourceId,
            @RequestParam UUID survivingId,
            @RequestParam String mergedBy,
            @RequestParam(defaultValue = "Duplicate registration") String reason) {
        return mpiService.mergePatients(facilityId, sourceId, survivingId, mergedBy, reason);
    }

    @PostMapping("/api/v1/mpi/patients/unmerge")
    @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_facility:manage')")
    @Operation(summary = "Reverse a prior MPI patient merge by mergeHistoryId")
    public Object unmerge(
            @RequestParam UUID mergeHistoryId,
            @RequestParam String reversedBy,
            @RequestParam(defaultValue = "False positive merge") String reason) {
        return mpiService.unmergePatients(mergeHistoryId, reversedBy, reason);
    }

    @GetMapping(value = "/fhir/r4/Patient/{id}", produces = "application/fhir+json")
    @Operation(summary = "Read HL7 FHIR R4 Patient resource with ETag")
    public ResponseEntity<String> readFhirPatient(@PathVariable UUID id) {
        PatientAggregateEntity entity = patientRepository.findById(id)
                .map(mpiService::resolveSurviving)
                .orElse(null);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        IParser json = FHIR_R4.newJsonParser().setPrettyPrint(true);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/fhir+json"))
                .eTag("W/\"" + entity.getVersion() + "\"")
                .body(json.encodeResourceToString(toFhirPatient(entity)));
    }

    @PutMapping(value = "/fhir/r4/Patient/{id}", consumes = {"application/fhir+json", "application/json"}, produces = "application/fhir+json")
    @PreAuthorize("hasAuthority('PERM_patient:write')")
    @Operation(summary = "Update HL7 FHIR R4 Patient resource enforcing If-Match ETag optimistic concurrency")
    public ResponseEntity<String> updateFhirPatient(
            @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody String fhirPatientJson) {
        PatientAggregateEntity entity = patientRepository.findById(id).orElse(null);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        String currentEtag = "W/\"" + entity.getVersion() + "\"";
        String rawVersion = Long.toString(entity.getVersion());
        if (ifMatch != null && !ifMatch.isBlank()
                && !Objects.equals(ifMatch.trim(), currentEtag)
                && !Objects.equals(ifMatch.replace("\"", "").replace("W/", "").trim(), rawVersion)) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                    .contentType(MediaType.parseMediaType("application/fhir+json"))
                    .body("{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"conflict\",\"diagnostics\":\"Stale ETag in If-Match header; expected " + currentEtag + "\"}]}");
        }
        IParser json = FHIR_R4.newJsonParser().setPrettyPrint(true);
        Patient incoming = json.parseResource(Patient.class, fhirPatientJson);
        if (!incoming.getName().isEmpty()) {
            if (incoming.getNameFirstRep().hasFamily()) entity.setFamilyName(incoming.getNameFirstRep().getFamily());
            if (!incoming.getNameFirstRep().getGiven().isEmpty()) {
                entity.setGivenName(incoming.getNameFirstRep().getGivenAsSingleText());
            }
        }
        if (incoming.hasActive()) entity.setActive(incoming.getActive());
        if (incoming.hasGender()) entity.setGender(incoming.getGender().toCode().toUpperCase());
        if (!incoming.getTelecom().isEmpty() && incoming.getTelecomFirstRep().hasValue()) {
            entity.setPrimaryPhone(incoming.getTelecomFirstRep().getValue());
        }
        entity.setUpdatedAt(Instant.now());
        PatientAggregateEntity saved = patientRepository.save(entity);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/fhir+json"))
                .eTag("W/\"" + saved.getVersion() + "\"")
                .body(json.encodeResourceToString(toFhirPatient(saved)));
    }

    public static Patient toFhirPatient(PatientAggregateEntity e) {
        Patient p = new Patient();
        p.setId(e.getId().toString());
        p.getMeta().setVersionId(Long.toString(e.getVersion()));
        p.addIdentifier().setSystem("urn:opes:mrn").setValue(e.getMrn());
        p.addName().setFamily(e.getFamilyName()).addGiven(e.getGivenName());
        p.setActive(e.isActive());
        p.setDeceased(new BooleanType(e.isDeceased()));
        p.setBirthDate(Date.from(e.getBirthDate().atStartOfDay(ZoneId.systemDefault()).toInstant()));
        p.setGender(AdministrativeGender.fromCode(e.getGender().toLowerCase()));
        if (e.getPrimaryPhone() != null) {
            p.addTelecom().setSystem(ContactPoint.ContactPointSystem.PHONE).setValue(e.getPrimaryPhone());
        }
        return p;
    }
}
