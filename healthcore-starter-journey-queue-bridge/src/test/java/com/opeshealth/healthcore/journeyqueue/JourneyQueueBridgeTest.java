package com.opeshealth.healthcore.journeyqueue;

import com.opeshealth.healthcore.journeyqueue.auto.JourneyQueueBridgeAutoConfiguration.DepartmentQueueAdapter;
import com.opeshealth.healthcore.queue.domain.QueueManagementEntities.DepartmentQueueEntryEntity;
import com.opeshealth.healthcore.queue.domain.QueueManagementEntities.QueuePriority;
import com.opeshealth.healthcore.queue.domain.QueueManagementEntities.QueueStatus;
import com.opeshealth.healthcore.queue.service.QueueManagementService;
import com.opeshealth.healthcore.queue.service.QueueManagementService.DepartmentQueueEntryRepository;
import com.opeshealth.healthcore.queue.service.QueueManagementService.EnqueueRequest;
import com.opeshealth.healthcore.workflow.journey.ClinicalJourneyContracts.PaymentClearanceOutcome;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyEntities.DepartmentOrderRoutingEntity;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyEntities.OrderTargetDepartment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JourneyQueueBridgeTest {
    private QueueManagementService queue;
    private DepartmentQueueEntryRepository entries;
    private DepartmentQueueAdapter adapter;

    @BeforeEach
    void setUp() {
        queue = mock(QueueManagementService.class);
        entries = mock(DepartmentQueueEntryRepository.class);
        adapter = new DepartmentQueueAdapter(queue, entries);
    }

    @Test
    void automaticallyRoutesLaboratoryAndRadioOrdersToMatchingDepartments() {
        DepartmentOrderRoutingEntity lab = routing(OrderTargetDepartment.LABORATORY);
        DepartmentQueueEntryEntity labEntry = matchingEntry(lab, "LAB_COLLECTION");
        when(queue.enqueue(eq(lab.getFacilityId()), any(EnqueueRequest.class), eq("dr-1")))
                .thenReturn(labEntry);
        assertEquals(labEntry.getId(), adapter.createOrGetQueueEntry(lab, true, "dr-1"));

        ArgumentCaptor<EnqueueRequest> capture = ArgumentCaptor.forClass(EnqueueRequest.class);
        verify(queue).enqueue(eq(lab.getFacilityId()), capture.capture(), eq("dr-1"));
        EnqueueRequest labReq = capture.getValue();
        assertEquals("LAB_COLLECTION", labReq.departmentCode());
        assertEquals(QueuePriority.STAT, labReq.priority());
        assertEquals(PaymentClearanceOutcome.PAYMENT_PENDING.name(), labReq.financialClearanceState());
        assertEquals(lab.getOrderId(), labReq.orderId());
        assertEquals("ORDER-" + lab.getOrderId() + "-LABORATORY", labReq.idempotencyKey());

        reset(queue);
        DepartmentOrderRoutingEntity rad = routing(OrderTargetDepartment.RADIOLOGY);
        DepartmentQueueEntryEntity radEntry = matchingEntry(rad, "RADIOLOGY");
        when(queue.enqueue(eq(rad.getFacilityId()), any(EnqueueRequest.class), eq("dr-2")))
                .thenReturn(radEntry);
        assertEquals(radEntry.getId(), adapter.createOrGetQueueEntry(rad, false, "dr-2"));
        verify(queue).enqueue(eq(rad.getFacilityId()),
                argThat(request -> request.priority() == QueuePriority.ROUTINE
                        && request.departmentCode().equals("RADIOLOGY")), eq("dr-2"));
    }

    @Test
    void heldQueueIsActivatedOnlyAfterMatchingPaymentRelease() {
        DepartmentOrderRoutingEntity routing = routing(OrderTargetDepartment.PHARMACY);
        routing.setFinancialClearance(PaymentClearanceOutcome.PAID);
        DepartmentQueueEntryEntity entry = matchingEntry(routing, "PHARMACY");
        entry.setQueueStatus(QueueStatus.WAITING);
        routing.setQueueEntryId(entry.getId());
        when(entries.findById(entry.getId())).thenReturn(Optional.of(entry));
        adapter.synchronizeFinancialClearance(routing);
        verify(queue).updateFinancialClearance(routing.getFacilityId(), entry.getId(), "PAID");

        entry.setQueueStatus(QueueStatus.CANCELLED);
        assertThrows(IllegalStateException.class,
                () -> adapter.synchronizeFinancialClearance(routing));
        verify(queue, times(1)).updateFinancialClearance(any(), any(), anyString());
    }

    @Test
    void fulfillmentRequiresCompletedWorklistTaskAndPreventsPatientRebinding() {
        DepartmentOrderRoutingEntity routing = routing(OrderTargetDepartment.LABORATORY);
        DepartmentQueueEntryEntity entry = matchingEntry(routing, "LAB_COLLECTION");
        routing.setQueueEntryId(entry.getId());
        when(entries.findById(entry.getId())).thenReturn(Optional.of(entry));

        entry.setQueueStatus(QueueStatus.IN_SERVICE);
        assertThrows(IllegalStateException.class, () -> adapter.requireQueueCompleted(routing));
        entry.setQueueStatus(QueueStatus.COMPLETED);
        assertDoesNotThrow(() -> adapter.requireQueueCompleted(routing));

        entry.setPatientId(UUID.randomUUID());
        assertThrows(SecurityException.class, () -> adapter.requireQueueCompleted(routing));
        assertThrows(SecurityException.class, () -> adapter.synchronizeFinancialClearance(routing));
    }

    @Test
    void externalPharmacyNeverCreatesInternalDispensingQueue() {
        DepartmentOrderRoutingEntity external = routing(OrderTargetDepartment.EXTERNAL_PHARMACY);
        assertThrows(IllegalArgumentException.class,
                () -> adapter.createOrGetQueueEntry(external, false, "dr-1"));
        verifyNoInteractions(queue, entries);
    }

    private static DepartmentOrderRoutingEntity routing(OrderTargetDepartment department) {
        DepartmentOrderRoutingEntity route = new DepartmentOrderRoutingEntity();
        route.setFacilityId(UUID.randomUUID());
        route.setPatientId(UUID.randomUUID());
        route.setEncounterId(UUID.randomUUID());
        route.setOrderId(UUID.randomUUID());
        route.setTargetDepartment(department);
        route.setFinancialClearance(PaymentClearanceOutcome.PAYMENT_PENDING);
        return route;
    }

    private static DepartmentQueueEntryEntity matchingEntry(DepartmentOrderRoutingEntity route, String department) {
        DepartmentQueueEntryEntity entry = new DepartmentQueueEntryEntity();
        entry.setFacilityId(route.getFacilityId());
        entry.setPatientId(route.getPatientId());
        entry.setEncounterId(route.getEncounterId());
        entry.setOrderId(route.getOrderId());
        entry.setDepartmentCode(department);
        return entry;
    }
}
