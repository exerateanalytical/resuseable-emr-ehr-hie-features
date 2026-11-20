/*
 * =============================================================================
 * Opes HealthCore SDK — Surgery WHO Checklist & PACU Aldrete Tests
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.AnesthesiaPacuLogEntity;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.SurgicalScheduleEntity;
import com.opeshealth.healthcore.ops.service.HospitalOpsService;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.AnesthesiaPacuRepository;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.SurgicalScheduleRepository;
import com.opeshealth.healthcore.starters.SurgeryOtStarter.PacuAldreteAssessmentRequest;
import com.opeshealth.healthcore.starters.SurgeryOtStarter.PacuDischargeDecision;
import com.opeshealth.healthcore.starters.SurgeryOtStarter.SurgicalPerioperativeService;
import com.opeshealth.healthcore.starters.SurgeryOtStarter.WhoSurgicalChecklistSubmission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SurgicalPerioperativeTest {

    private HospitalOpsService hospitalOps;
    private SurgicalScheduleRepository scheduleRepo;
    private AnesthesiaPacuRepository pacuRepo;
    private SurgicalPerioperativeService service;
    private UUID facilityId;

    @BeforeEach
    void setUp() {
        facilityId = UUID.randomUUID();
        FacilityContext.setFacilityId(facilityId);
        hospitalOps = mock(HospitalOpsService.class);
        scheduleRepo = mock(SurgicalScheduleRepository.class);
        pacuRepo = mock(AnesthesiaPacuRepository.class);
        service = new SurgicalPerioperativeService(hospitalOps, scheduleRepo, pacuRepo);
    }

    @AfterEach
    void tearDown() { FacilityContext.clear(); }

    @Test
    void blocksSurgicalClearanceWhenWhoChecklistIncomplete() {
        UUID schedId = UUID.randomUUID();
        SurgicalScheduleEntity sched = new SurgicalScheduleEntity();
        sched.setFacilityId(facilityId);
        when(scheduleRepo.findByIdAndFacilityId(schedId, facilityId)).thenReturn(Optional.of(sched));

        // Incorrect sponge/instrument count must fail closed
        assertThrows(IllegalStateException.class, () ->
                service.completeWhoSurgicalSafetyChecklist(new WhoSurgicalChecklistSubmission(
                        schedId, true, true, true, true, false, null)));
    }

    @Test
    void evaluatesModifiedAldretePacuDischargeCriteria() {
        when(pacuRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UUID schedId = UUID.randomUUID();
        AnesthesiaPacuLogEntity priorAnesthesia = new AnesthesiaPacuLogEntity();
        priorAnesthesia.setFacilityId(facilityId);
        priorAnesthesia.setSurgeryId(schedId);
        priorAnesthesia.setInductionAgent("synthetic test agent");
        priorAnesthesia.setAnesthetistId("anesthetist-test");
        when(pacuRepo.findTopByFacilityIdAndSurgeryIdOrderByRecordedAtDesc(facilityId, schedId))
                .thenReturn(Optional.of(priorAnesthesia));

        // Score 9/10 with respiration=2 -> cleared
        PacuDischargeDecision cleared = service.evaluateAndRecordPacuAldreteScore(
                new PacuAldreteAssessmentRequest(schedId, 2, 2, 2, 2, 1, 150, "ASA_II"));
        assertTrue(cleared.eligibleForWardDischarge());
        assertEquals(9, cleared.totalAldreteScore());

        // Score 9/10 but respiration=1 -> retain in PACU
        PacuDischargeDecision retained = service.evaluateAndRecordPacuAldreteScore(
                new PacuAldreteAssessmentRequest(schedId, 2, 1, 2, 2, 2, 250, "ASA_III"));
        assertFalse(retained.eligibleForWardDischarge());

        // Out-of-range domain score rejected
        assertThrows(IllegalArgumentException.class, () ->
                service.evaluateAndRecordPacuAldreteScore(
                        new PacuAldreteAssessmentRequest(schedId, 3, 2, 2, 2, 2, 100, "ASA_I")));
    }
}
