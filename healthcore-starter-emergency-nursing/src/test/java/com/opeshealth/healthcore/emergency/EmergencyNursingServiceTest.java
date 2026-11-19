/*
 * =============================================================================
 * Opes HealthCore SDK — Emergency Triage & Inpatient Nursing Unit Tests
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.emergency;

import com.opeshealth.healthcore.emergency.domain.EmergencyNursingEntities.*;
import com.opeshealth.healthcore.emergency.service.EmergencyNursingService;
import com.opeshealth.healthcore.emergency.service.EmergencyNursingService.*;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmergencyNursingServiceTest {

    private EmergencyVisitRepository edRepo;
    private NursingAssignmentRepository assignRepo;
    private News2ObservationRepository news2Repo;
    private DischargeReadinessRepository dischargeRepo;
    private ClinicalAuditService auditService;
    private EmergencyNursingService service;
    private UUID facilityId;

    @BeforeEach
    void setUp() {
        edRepo = mock(EmergencyVisitRepository.class);
        assignRepo = mock(NursingAssignmentRepository.class);
        news2Repo = mock(News2ObservationRepository.class);
        dischargeRepo = mock(DischargeReadinessRepository.class);
        auditService = mock(ClinicalAuditService.class);
        service = new EmergencyNursingService(edRepo, assignRepo, news2Repo, dischargeRepo, auditService);
        facilityId = UUID.randomUUID();
        FacilityContext.setFacilityId(facilityId);
    }

    @AfterEach
    void tearDown() {
        FacilityContext.clear();
    }

    @Test
    void calculatesNews2EscalationForSepsisAndHypoxia() {
        when(news2Repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Normal patient -> LOW, no escalation
        News2ObservationEntity normal = service.recordNews2Observation(new News2RecordingRequest(
                UUID.randomUUID(), null, 16, 98, false, 122, 74, 36.8, "A", "rn-1"));
        assertEquals(0, normal.getTotalScore());
        assertEquals("LOW", normal.getRiskBand());
        assertFalse(normal.isEscalationRequired());

        // Deteriorating septic patient -> HIGH, escalation triggered
        News2ObservationEntity critical = service.recordNews2Observation(new News2RecordingRequest(
                UUID.randomUUID(), null, 28, 89, true, 86, 134, 39.4, "C", "rn-1"));
        assertTrue(critical.getTotalScore() >= 7);
        assertEquals("HIGH", critical.getRiskBand());
        assertTrue(critical.isEscalationRequired());
    }

    @Test
    void enforcesSafeNurseToPatientRatioAndEsiValidation() {
        assertThrows(IllegalArgumentException.class, () ->
                service.registerEdTriage(new EdArrivalTriageRequest(
                        UUID.randomUUID(), null, "Chest pain", 0, "AMBULANCE", "RESUS-1", "rn-triage")));

        when(assignRepo.countByFacilityIdAndNurseIdAndActiveFlagTrue(facilityId, "rn-icu-1")).thenReturn(2L);
        assertThrows(ResponseStatusException.class, () ->
                service.assignNurseToPatient(new NurseAssignmentRequest(
                        "ICU", "ICU-03", UUID.randomUUID(), "rn-icu-1", "NIGHT", 2)));
    }

    @Test
    void dischargeReadinessRequiresAllFiveMultiDisciplinaryGates() {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        when(dischargeRepo.findByFacilityIdAndEncounterId(facilityId, encounterId)).thenReturn(Optional.empty());
        when(dischargeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DischargeReadinessEntity incomplete = service.updateDischargeReadiness(new DischargeReadinessRequest(
                patientId, encounterId, true, true, true, false, true, "rn-ward-2"));
        assertFalse(incomplete.isReadyForDischarge());

        DischargeReadinessEntity complete = service.updateDischargeReadiness(new DischargeReadinessRequest(
                patientId, encounterId, true, true, true, true, true, "rn-ward-2"));
        assertTrue(complete.isReadyForDischarge());
    }
}
