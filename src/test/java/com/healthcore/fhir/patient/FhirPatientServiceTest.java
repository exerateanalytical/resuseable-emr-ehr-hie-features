package com.healthcore.fhir.patient;

import com.healthcore.hie.HieOutboxRepository;
import com.healthcore.mpi.PatientEntity;
import com.healthcore.mpi.PatientIdentifierRepository;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FhirPatientServiceTest {

    @Mock
    private FhirPatientRepository patientRepository;
    @Mock
    private PatientIdentifierRepository identifierRepository;
    @Mock
    private HieOutboxRepository outboxRepository;

    private FhirPatientMapper mapper;
    private FhirPatientService service;

    @BeforeEach
    void setUp() {
        mapper = new FhirPatientMapper();
        service = new FhirPatientService(patientRepository, identifierRepository, mapper, outboxRepository);
    }

    @Test
    void parsesAdvancedFhirDemographicsAndPersistsPatient() {
        String inputJson = """
                {
                  "resourceType": "Patient",
                  "active": true,
                  "identifier": [
                    { "use": "official", "system": "urn:cm:national-id", "value": "CM-112233445" }
                  ],
                  "name": [
                    { "use": "official", "family": "Mbarga", "given": ["Jean", "Paul"], "prefix": ["M."] }
                  ],
                  "gender": "male",
                  "birthDate": "1991-05-14",
                  "maritalStatus": {
                    "coding": [{ "system": "http://terminology.hl7.org/CodeSystem/v3-MaritalStatus", "code": "M" }]
                  },
                  "telecom": [
                    { "system": "phone", "value": "+237677001122", "use": "mobile", "rank": 1 },
                    { "system": "email", "value": "jean.mbarga@example.cm", "use": "home" }
                  ],
                  "address": [
                    { "use": "home", "line": ["Rue Joss, Bonanjo"], "city": "Douala", "state": "Littoral", "country": "CM" }
                  ],
                  "contact": [
                    {
                      "relationship": [{ "coding": [{ "code": "C", "display": "Emergency Contact" }] }],
                      "name": { "family": "Mbarga", "given": ["Claire"] },
                      "telecom": [{ "system": "phone", "value": "+237699887766" }]
                    }
                  ],
                  "communication": [
                    { "language": { "coding": [{ "code": "fr", "display": "French" }] }, "preferred": true }
                  ]
                }
                """;

        UUID generatedId = UUID.randomUUID();
        when(patientRepository.save(any(PatientEntity.class))).thenAnswer(inv -> {
            PatientEntity e = inv.getArgument(0);
            var field = PatientEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(e, generatedId);
            return e;
        });
        when(identifierRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.createPatient(inputJson);

        ArgumentCaptor<PatientEntity> captor = ArgumentCaptor.forClass(PatientEntity.class);
        verify(patientRepository).save(captor.capture());
        PatientEntity persisted = captor.getValue();

        assertEquals("Mbarga", persisted.getFamilyName());
        assertEquals("Jean", persisted.getGivenName());
        assertEquals(List.of("Paul"), persisted.getMiddleNames());
        assertEquals("M.", persisted.getNamePrefix());
        assertEquals("M", persisted.getMaritalStatusCode());
        assertEquals(LocalDate.of(1991, 5, 14), persisted.getDateOfBirth());
        assertEquals("fr", persisted.getPreferredLanguage());
        assertEquals(2, persisted.getTelecoms().size());
        assertEquals(1, persisted.getAddresses().size());
        assertEquals("Douala", persisted.getAddresses().get(0).getCity());
        assertEquals(1, persisted.getContacts().size());
        assertEquals("Claire", persisted.getContacts().get(0).getGivenName());

        Patient returned = mapper.parseFhirPatient(result.fhirJson());
        assertEquals(generatedId.toString(), returned.getIdElement().getIdPart());
        assertEquals("Douala", returned.getAddressFirstRep().getCity());
    }

    @Test
    void deactivatesPatientWithoutDeletingClinicalHistory() throws Exception {
        UUID id = UUID.randomUUID();
        PatientEntity entity = new PatientEntity();
        var field = PatientEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
        entity.setActive(true);

        when(patientRepository.findById(id)).thenReturn(Optional.of(entity));

        service.deactivatePatient(id);

        assertFalse(entity.isActive());
        verify(patientRepository).save(entity);
    }
}
