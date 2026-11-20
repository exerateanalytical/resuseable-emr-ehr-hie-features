package com.opeshealth.healthcore.workflow;

import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import com.opeshealth.healthcore.workflow.domain.WorkflowEntities.*;
import com.opeshealth.healthcore.workflow.service.WorkflowOrchestrationService;
import com.opeshealth.healthcore.workflow.service.WorkflowOrchestrationService.*;
import com.opeshealth.healthcore.workflow.spi.WorkflowSpi.WorkflowDomainCategory;
import com.opeshealth.healthcore.workflow.spi.WorkflowSpi.WorkflowPriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkflowOrchestrationServiceTest {

    private WorkflowDefinitionRepository defRepo;
    private WorkflowInstanceRepository instRepo;
    private WorkflowStepLogRepository stepLogRepo;
    private OutboxRelayService.OutboxRepository outboxRepo;
    private WorkflowOrchestrationService service;
    private UUID facilityId;

    @BeforeEach
    void setUp() {
        defRepo = mock(WorkflowDefinitionRepository.class);
        instRepo = mock(WorkflowInstanceRepository.class);
        stepLogRepo = mock(WorkflowStepLogRepository.class);
        outboxRepo = mock(OutboxRelayService.OutboxRepository.class);
        when(defRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(instRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(stepLogRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(outboxRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new WorkflowOrchestrationService(defRepo, instRepo, stepLogRepo, outboxRepo, List.of(), List.of());
        facilityId = UUID.randomUUID();
        FacilityContext.setFacilityId(facilityId);
    }

    @AfterEach
    void tearDown() {
        FacilityContext.clear();
    }

    @Test
    void seedsCatalogStartsWorkflowAndEnforcesValidStageTransitions() {
        when(defRepo.findByFacilityIdAndWorkflowCode(eq(facilityId), any())).thenReturn(Optional.empty());
        List<WorkflowDefinitionEntity> seeded = service.seedCatalogForFacility(facilityId);
        assertFalse(seeded.isEmpty());

        WorkflowDefinitionEntity def = service.registerCustomDefinition(
                facilityId, "DISCHARGE_FLOW", WorkflowDomainCategory.EMR, "INITIATED",
                Map.of("INITIATED", Set.of("MED_REC_DONE"), "MED_REC_DONE", Set.of("DISCHARGED")),
                Set.of("DISCHARGED"), 60);
        when(defRepo.findByFacilityIdAndWorkflowCode(facilityId, "DISCHARGE_FLOW")).thenReturn(Optional.of(def));

        WorkflowInstanceEntity inst = service.startWorkflow(
                facilityId, "DISCHARGE_FLOW", UUID.randomUUID(), "ENC-01",
                WorkflowPriority.URGENT, Map.of(), "rn.1", "NURSE");
        assertEquals("INITIATED", inst.getCurrentStage());

        when(instRepo.findById(inst.getId())).thenReturn(Optional.of(inst));
        assertThrows(IllegalStateException.class, () ->
                service.transitionWorkflow(inst.getId(), "DISCHARGED", "rn.1", "NURSE", "Skip step", Map.of()));

        WorkflowInstanceEntity next = service.transitionWorkflow(
                inst.getId(), "MED_REC_DONE", "pharm.1", "PHARMACIST", "Med rec complete", Map.of());
        assertEquals("MED_REC_DONE", next.getCurrentStage());
    }
}
