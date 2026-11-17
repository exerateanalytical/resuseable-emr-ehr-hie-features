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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Exposes HL7 FHIR R4 {@code /fhir/r4/Patient} endpoints with {@code ETag} optimistic concurrency
 * alongside probabilistic MPI matching and Golden Record merge endpoints.
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
    @Operation(summary = "Register a new patient Golden Record with encrypted National ID blind index")
    public PatientAggregateEntity register(@RequestBody RegisterPatientCommand cmd) {
        return mpiService.registerPatient(cmd);
    }

    @GetMapping("/api/v1/mpi/patients/match")
    @Operation(summary = "Run probabilistic Fellegi-Sunter duplicate patient match")
    public List<MatchCandidate> match(
            @RequestParam String familyName,
            @RequestParam String givenName,
            @RequestParam java.time.LocalDate birthDate,
            @RequestParam String gender,
            @RequestParam(required = false) String phone) {
        return mpiService.findProbabilisticMatches(familyName, givenName, birthDate, gender, phone);
    }

    @PostMapping("/api/v1/mpi/patients/merge")
    @Operation(summary = "Reversibly merge duplicate patient record into surviving Golden Record")
    public Object merge(
            @RequestParam UUID facilityId,
            @RequestParam UUID sourceId,
            @RequestParam UUID survivingId,
            @RequestParam String mergedBy,
            @RequestParam(defaultValue = "Duplicate registration") String reason) {
        return mpiService.mergePatients(facilityId, sourceId, survivingId, mergedBy, reason);
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
