package com.opeshealth.healthcore.ops;

import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.*;
import com.opeshealth.healthcore.ops.service.HospitalOpsService;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HospitalOpsServiceTest {

    private BloodDonorRepository donorRepo;
    private BloodUnitRepository unitRepo;
    private TransfusionReactionRepository reactionRepo;
    private SurgicalScheduleRepository surgeryRepo;
    private AnesthesiaPacuRepository pacuRepo;
    private HospitalOpsService service;

    @BeforeEach
    void setUp() {
        donorRepo = mock(BloodDonorRepository.class);
        unitRepo = mock(BloodUnitRepository.class);
        reactionRepo = mock(TransfusionReactionRepository.class);
        surgeryRepo = mock(SurgicalScheduleRepository.class);
        pacuRepo = mock(AnesthesiaPacuRepository.class);
        when(donorRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(unitRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(reactionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(surgeryRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(pacuRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new HospitalOpsService(
                unitRepo, donorRepo, reactionRepo, surgeryRepo, pacuRepo,
                mock(TelemedicineRepository.class), mock(ColdChainRepository.class),
                mock(BiomedMaintenanceRepository.class), mock(CrvsRepository.class),
                mock(CredentialRepository.class), mock(WorkforceRosterRepository.class),
                mock(OutboxRelayService.OutboxRepository.class));
    }

    @Test
    void enforcesTwoPersonBedsideVerificationForBloodIssueAndWhoGateForSurgery() {
        BloodComponentUnitEntity unit = new BloodComponentUnitEntity();
        unit.setStatus("AVAILABLE");
        unit.setExpiryDate(LocalDate.now().plusDays(7));
        when(unitRepo.findById(unit.getId())).thenReturn(Optional.of(unit));

        assertThrows(SecurityException.class, () ->
                service.verifyAndIssueBloodUnit(unit.getId(), UUID.randomUUID(), "rn.1", "rn.1"));

        BloodComponentUnitEntity issued = service.verifyAndIssueBloodUnit(
                unit.getId(), UUID.randomUUID(), "rn.1", "rn.2");
        assertEquals("ISSUED", issued.getStatus());

        SurgicalScheduleEntity sched = new SurgicalScheduleEntity();
        sched.setWhoChecklistCompleted(false);
        when(surgeryRepo.findById(sched.getId())).thenReturn(Optional.of(sched));
        assertThrows(IllegalStateException.class, () -> service.completeSurgeryWithWhoGate(sched.getId(), "UDI-001"));

        sched.setWhoChecklistCompleted(true);
        SurgicalScheduleEntity done = service.completeSurgeryWithWhoGate(sched.getId(), "UDI-001");
        assertEquals("COMPLETED", done.getStatus());
    }
}
