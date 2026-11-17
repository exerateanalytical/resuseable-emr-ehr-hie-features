package com.healthcore.hie;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.healthcore.emr.EncounterEntity;
import com.healthcore.emr.ObservationEntity;
import com.healthcore.mpi.PatientEntity;
import org.hl7.fhir.r4.model.*;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.util.List;

@Component
public class FhirR4Mapper {

    private final FhirContext fhirContext = FhirContext.forR4();

    public Patient toFhirPatient(PatientEntity entity) {
        Patient patient = new Patient();
        patient.setId(entity.getId().toString());
        patient.addIdentifier()
                .setSystem("urn:healthcore:enterprise-mpi")
                .setValue(entity.getEnterpriseId());

        patient.addName()
                .setFamily(entity.getFamilyName())
                .addGiven(entity.getGivenName());

        patient.setBirthDate(Date.valueOf(entity.getDateOfBirth()));
        patient.setGender(mapGender(entity.getSex()));

        if (entity.getPrimaryPhone() != null) {
            patient.addTelecom()
                    .setSystem(ContactPoint.ContactPointSystem.PHONE)
                    .setValue(entity.getPrimaryPhone());
        }
        return patient;
    }

    public Encounter toFhirEncounter(EncounterEntity entity) {
        Encounter encounter = new Encounter();
        encounter.setId(entity.getId().toString());
        encounter.setSubject(new Reference("Patient/" + entity.getPatientId()));
        encounter.setServiceProvider(new Reference("Organization/" + entity.getFacilityId()));
        encounter.setStatus("COMPLETED".equals(entity.getStatus())
                ? Encounter.EncounterStatus.FINISHED
                : Encounter.EncounterStatus.INPROGRESS);
        encounter.setClass_(new Coding("http://terminology.hl7.org/CodeSystem/v3-ActCode",
                entity.getEncounterType(), entity.getEncounterType()));

        Period period = new Period();
        period.setStart(java.util.Date.from(entity.getStartedAt()));
        if (entity.getEndedAt() != null) {
            period.setEnd(java.util.Date.from(entity.getEndedAt()));
        }
        encounter.setPeriod(period);
        return encounter;
    }

    public Observation toFhirObservation(ObservationEntity entity) {
        Observation obs = new Observation();
        obs.setId(entity.getId().toString());
        obs.setStatus(Observation.ObservationStatus.FINAL);
        obs.setSubject(new Reference("Patient/" + entity.getPatientId()));
        obs.setEncounter(new Reference("Encounter/" + entity.getEncounterId()));
        obs.getCode().addCoding()
                .setSystem("http://loinc.org")
                .setCode(entity.getLoincCode())
                .setDisplay(entity.getDisplayName());

        if (entity.getValueNumeric() != null) {
            obs.setValue(new Quantity()
                    .setValue(entity.getValueNumeric())
                    .setUnit(entity.getUnit()));
        } else if (entity.getValueText() != null) {
            obs.setValue(new StringType(entity.getValueText()));
        }
        return obs;
    }

    public String toTransactionBundleJson(PatientEntity patient, EncounterEntity encounter, List<ObservationEntity> observations) {
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.TRANSACTION);

        addPutEntry(bundle, "Patient/" + patient.getId(), toFhirPatient(patient));
        addPutEntry(bundle, "Encounter/" + encounter.getId(), toFhirEncounter(encounter));
        for (ObservationEntity obs : observations) {
            addPutEntry(bundle, "Observation/" + obs.getId(), toFhirObservation(obs));
        }

        IParser parser = fhirContext.newJsonParser().setPrettyPrint(false);
        return parser.encodeResourceToString(bundle);
    }

    private void addPutEntry(Bundle bundle, String fullUrl, Resource resource) {
        bundle.addEntry()
                .setFullUrl(fullUrl)
                .setResource(resource)
                .getRequest()
                .setMethod(Bundle.HTTPVerb.PUT)
                .setUrl(fullUrl);
    }

    private Enumerations.AdministrativeGender mapGender(String sex) {
        return switch (sex.toUpperCase()) {
            case "MALE" -> Enumerations.AdministrativeGender.MALE;
            case "FEMALE" -> Enumerations.AdministrativeGender.FEMALE;
            case "OTHER" -> Enumerations.AdministrativeGender.OTHER;
            default -> Enumerations.AdministrativeGender.UNKNOWN;
        };
    }
}
