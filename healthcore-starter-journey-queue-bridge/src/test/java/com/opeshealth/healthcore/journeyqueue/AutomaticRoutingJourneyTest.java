package com.opeshealth.healthcore.journeyqueue;

import com.opeshealth.healthcore.journeyqueue.auto.JourneyQueueBridgeAutoConfiguration.DepartmentQueueAdapter;
import com.opeshealth.healthcore.queue.domain.QueueManagementEntities.DepartmentQueueEntryEntity;
import com.opeshealth.healthcore.queue.domain.QueueManagementEntities.QueueStatus;
import com.opeshealth.healthcore.queue.service.QueueManagementService;
import com.opeshealth.healthcore.queue.service.QueueManagementService.DepartmentQueueEntryRepository;
import com.opeshealth.healthcore.queue.service.QueueManagementService.EnqueueRequest;
import com.opeshealth.healthcore.workflow.journey.ClinicalJourneyContracts.FacilityBillingPolicyConfig;
import com.opeshealth.healthcore.workflow.journey.ClinicalJourneyContracts.PaymentClearanceOutcome;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyEntities.DepartmentOrderRoutingEntity;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyEntities.OrderRoutingStatus;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyEntities.OrderTargetDepartment;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyOrchestratorService;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyOrchestratorService.DepartmentOrderRoutingRepository;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyOrchestratorService.DurableProcessedEventRepository;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyOrchestratorService.RouteSignedOrderCommand;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies the actual journey -> queue adapter invocation, not just adapter mapping.
 * Persists synthetic state in stub repositories; PostgreSQL IT is separate.
 */
class AutomaticRoutingJourneyTest {

    @Test
    void signedOrderCreatesHeldLabQueuePaymentReleasesItAndCompletedWorkUnblocksParent() {
        QueueManagementService queueService = mock(QueueManagementService.class);
        DepartmentQueueEntryRepository entries = mock(DepartmentQueueEntryRepository.class);
        DepartmentOrderRoutingRepository routingRepo = mock(DepartmentOrderRoutingRepository.class);
        DurableProcessedEventRepository processedRepo = mock(DurableProcessedEventRepository.class);
        Map<UUID, DepartmentOrderRoutingEntity> routings = new HashMap<>();
        Map<UUID, DepartmentQueueEntryEntity> queues = new HashMap<>();

        when(routingRepo.save(any(DepartmentOrderRoutingEntity.class))).thenAnswer(invocation -> {
            DepartmentOrderRoutingEntity routing = invocation.getArgument(0);
            routings.put(routing.getId(), routing);
            return routing;
        });
        when(routingRepo.findByFacilityIdAndOrderId(any(UUID.class), any(UUID.class))).thenAnswer(invocation -> {
            UUID facility = invocation.getArgument(0);
            UUID orderId = invocation.getArgument(1);
            return routings.values().stream().filter(r -> facility.equals(r.getFacilityId())
                    && orderId.equals(r.getOrderId())).findFirst();
        });
        when(routingRepo.findByFacilityIdAndIdempotencyKey(any(UUID.class), anyString()))
                .thenAnswer(invocation -> {
                    UUID facility = invocation.getArgument(0);
                    String key = invocation.getArgument(1);
                    return routings.values().stream().filter(r -> facility.equals(r.getFacilityId())
                            && key.equals(r.getIdempotencyKey())).findFirst();
                });

        when(queueService.enqueue(any(UUID.class), any(EnqueueRequest.class), anyString()))
                .thenAnswer(invocation -> {
                    UUID facility = invocation.getArgument(0);
                    EnqueueRequest command = invocation.getArgument(1);
                    return queues.values().stream()
                            .filter(q -> facility.equals(q.getFacilityId())
                                    && command.idempotencyKey().equals(q.getIdempotencyKey()))
                            .findFirst()
                            .orElseGet(() -> {
                                DepartmentQueueEntryEntity entry = new DepartmentQueueEntryEntity();
                                entry.setFacilityId(facility);
                                entry.setPatientId(command.patientId());
                                entry.setEncounterId(command.encounterId());
                                entry.setOrderId(command.orderId());
                                entry.setDepartmentCode(command.departmentCode());
                                entry.setIdempotencyKey(command.idempotencyKey());
                                entry.setQueueStatus(QueueStatus.WAITING);
                                queues.put(entry.getId(), entry);
                                return entry;
                            });
                });
        when(entries.findById(any(UUID.class)))
                .thenAnswer(invocation -> Optional.ofNullable(queues.get(invocation.getArgument(0))));
        when(queueService.updateFinancialClearance(any(UUID.class), any(UUID.class), eq("PAID")))
                .thenAnswer(invocation -> {
                    DepartmentQueueEntryEntity entry = queues.get(invocation.getArgument(1));
                    entry.setQueueStatus(QueueStatus.READY);
                    return entry;
                });
        when(processedRepo.findByFacilityIdAndIdempotencyKey(any(UUID.class), anyString()))
                .thenReturn(Optional.empty());

        PatientJourneyOrchestratorService journey = new PatientJourneyOrchestratorService(
                null, null, routingRepo, null, null, processedRepo, null,
                null, java.util.List.of(), new DepartmentQueueAdapter(queueService, entries));

        UUID facility = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        UUID encounter = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        RouteSignedOrderCommand cmd = new RouteSignedOrderCommand(
                facility, patient, encounter, null, orderId, OrderTargetDepartment.LABORATORY,
                null, false, false, false, false, false, 5000, 0,
                "dr-synthetic", "LAB-ROUTE-" + orderId);
        DepartmentOrderRoutingEntity route = journey.routeSignedOrder(
                cmd, FacilityBillingPolicyConfig.defaultFacilityPolicy());

        assertEquals(OrderRoutingStatus.ROUTED_AWAITING_CLEARANCE, route.getRoutingStatus());
        assertNotNull(route.getQueueEntryId(), "A signed in-house lab order must create a queue entry");
        assertEquals(1, queues.size());
        DepartmentQueueEntryEntity queue = queues.get(route.getQueueEntryId());
        assertEquals("LAB_COLLECTION", queue.getDepartmentCode());
        assertEquals(QueueStatus.WAITING, queue.getQueueStatus());
        assertSame(route, journey.routeSignedOrder(
                cmd, FacilityBillingPolicyConfig.defaultFacilityPolicy()));
        assertEquals(1, queues.size(), "Duplicate signed order delivery cannot create a second queue");

        assertThrows(IllegalStateException.class,
                () -> journey.markRoutedOrderCompleted(facility, orderId));
        assertThrows(IllegalArgumentException.class,
                () -> journey.confirmPaymentAndReleaseOrder(
                        facility, orderId, PaymentClearanceOutcome.PAYMENT_PENDING,
                        "PENDING-IS-NOT-PAID", "cashier"));
        assertThrows(IllegalArgumentException.class,
                () -> journey.confirmPaymentAndReleaseOrder(
                        facility, orderId, PaymentClearanceOutcome.REJECTED_OR_EXPIRED,
                        "REJECTED-IS-NOT-PAID", "cashier"));
        assertEquals(QueueStatus.WAITING, queue.getQueueStatus());

        DepartmentOrderRoutingEntity released = journey.confirmPaymentAndReleaseOrder(
                facility, orderId, PaymentClearanceOutcome.PAID, "PAID-RECEIPT-1", "cashier");
        assertEquals(OrderRoutingStatus.ACTIVE_IN_DEPARTMENT, released.getRoutingStatus());
        assertEquals(QueueStatus.READY, queue.getQueueStatus());
        assertThrows(IllegalStateException.class,
                () -> journey.markRoutedOrderCompleted(facility, orderId));

        queue.setQueueStatus(QueueStatus.COMPLETED); // Simulates actual scientist's completed queue work.
        DepartmentOrderRoutingEntity finished = journey.markRoutedOrderCompleted(facility, orderId);
        assertEquals(OrderRoutingStatus.COMPLETED, finished.getRoutingStatus());
        java.time.Instant completed = finished.getCompletedAt();
        assertSame(finished, journey.markRoutedOrderCompleted(facility, orderId));
        assertEquals(completed, finished.getCompletedAt());
    }
}
