package com.healthcore.rx;

import com.healthcore.tenant.FacilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrescriptionServiceTest {

    @Mock
    private PrescriptionRepository prescriptionRepository;

    @Mock
    private PatientAllergyRepository allergyRepository;

    @InjectMocks
    private PrescriptionService prescriptionService;

    @BeforeEach
    void setUp() {
        FacilityContext.setFacilityId(UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        FacilityContext.clear();
    }

    @Test
    void blocksPrescriptionOnAllergyConflictWithoutOverride() {
        UUID patientId = UUID.randomUUID();
        PatientAllergyEntity allergy = new PatientAllergyEntity();
        allergy.setSubstanceName("Penicillin");
        allergy.setSeverity("SEVERE");

        when(allergyRepository.findByPatientIdAndStatus(patientId, "ACTIVE"))
                .thenReturn(List.of(allergy));

        var cmd = new PrescriptionService.IssuePrescriptionCommand(
                UUID.randomUUID(), patientId, "dr-ngono", "RX-01",
                "Penicillin V", "500mg", "TID", 7, 21, "After meals", null
        );

        assertThrows(IllegalArgumentException.class, () -> prescriptionService.issuePrescription(cmd));
    }

    @Test
    void allowsPrescriptionWhenClinicalOverrideProvided() {
        UUID patientId = UUID.randomUUID();
        PatientAllergyEntity allergy = new PatientAllergyEntity();
        allergy.setSubstanceName("Penicillin");
        allergy.setSeverity("MILD");

        when(allergyRepository.findByPatientIdAndStatus(patientId, "ACTIVE"))
                .thenReturn(List.of(allergy));
        when(prescriptionRepository.save(any(PrescriptionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var cmd = new PrescriptionService.IssuePrescriptionCommand(
                UUID.randomUUID(), patientId, "dr-ngono", "RX-01",
                "Penicillin V", "500mg", "TID", 7, 21, "Monitor closely", "No alternative available in clinic"
        );

        PrescriptionEntity saved = prescriptionService.issuePrescription(cmd);
        assertEquals("Penicillin V", saved.getMedicationName());
        assertEquals("No alternative available in clinic", saved.getAllergyOverrideReason());
    }
}
