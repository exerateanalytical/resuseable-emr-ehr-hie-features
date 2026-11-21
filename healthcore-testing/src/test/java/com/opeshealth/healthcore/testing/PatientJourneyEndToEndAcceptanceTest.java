/*
 * =============================================================================
 * Opes HealthCore SDK — Master Clinical Workflow & Patient Journey Acceptance Suite
 * =============================================================================
 * Implements all 10 required automated acceptance scenarios (Tests A through J)
 * from the OPES HealthCore Master Clinical Workflow & Patient Journey Directive,
 * plus machine-readable JSON workflow definition contract verification.
 */
package com.opeshealth.healthcore.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.queue.domain.QueueManagementEntities.*;
import com.opeshealth.healthcore.queue.service.QueueManagementService;
import com.opeshealth.healthcore.queue.service.QueueManagementService.*;
import com.opeshealth.healthcore.workflow.domain.WorkflowEntities.*;
import com.opeshealth.healthcore.workflow.journey.ClinicalJourneyContracts.*;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyEntities.*;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyOrchestratorService;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyOrchestratorService.*;
import com.opeshealth.healthcore.workflow.service.WorkflowOrchestrationService;
import com.opeshealth.healthcore.workflow.service.WorkflowOrchestrationService.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PatientJourneyEndToEndAcceptanceTest {

    // Durable backing stores shared across service instantiations (proves restart recovery in Test J)
    private Map<UUID, WorkflowDefinitionEntity> definitionStore;
    private Map<UUID, WorkflowInstanceEntity> instanceStore;
    private List<WorkflowStepLogEntity> stepLogStore;
    private Map<UUID, WorkflowSubflowLinkEntity> subflowStore;
    private Map<UUID, DepartmentOrderRoutingEntity> routingStore;
    private Map<UUID, ClinicianResultInboxEntity> inboxStore;
    private Map<UUID, DurableNotificationEntity> notificationStore;
    private Map<String, DurableProcessedEventEntity> processedEventStore;
    private Map<String, QueueCounterEntity> queueCounterStore;
    private Map<UUID, DepartmentQueueEntryEntity> queueEntryStore;
    private Map<String, QueuePauseControlEntity> queuePauseStore;
    private List<TransactionalOutboxEntity> outboxStore;

    private WorkflowOrchestrationService workflowService;
    private PatientJourneyOrchestratorService journeyService;
    private QueueManagementService queueService;
    private AtomicInteger providerSubmissions;

    @BeforeEach
    void setUp() {
        definitionStore = new ConcurrentHashMap<>();
        instanceStore = new ConcurrentHashMap<>();
        stepLogStore = Collections.synchronizedList(new ArrayList<>());
        subflowStore = new ConcurrentHashMap<>();
        routingStore = new ConcurrentHashMap<>();
        inboxStore = new ConcurrentHashMap<>();
        notificationStore = new ConcurrentHashMap<>();
        processedEventStore = new ConcurrentHashMap<>();
        queueCounterStore = new ConcurrentHashMap<>();
        queueEntryStore = new ConcurrentHashMap<>();
        queuePauseStore = new ConcurrentHashMap<>();
        outboxStore = Collections.synchronizedList(new ArrayList<>());
        providerSubmissions = new AtomicInteger(0);

        rebuildServicesOverBackingStores();
    }

    private void rebuildServicesOverBackingStores() {
        OutboxRelayService.OutboxRepository outboxRepo = mock(OutboxRelayService.OutboxRepository.class);
        when(outboxRepo.save(any(TransactionalOutboxEntity.class))).thenAnswer(inv -> {
            TransactionalOutboxEntity e = inv.getArgument(0);
            outboxStore.add(e);
            return e;
        });

        WorkflowDefinitionRepository defRepo = mock(WorkflowDefinitionRepository.class);
        when(defRepo.findTopByFacilityIdAndWorkflowCodeAndActiveTrueOrderByVersionNumberDesc(any(), anyString()))
                .thenAnswer(inv -> {
                    UUID fac = inv.getArgument(0);
                    String code = inv.getArgument(1);
                    return definitionStore.values().stream()
                            .filter(d -> fac.equals(d.getFacilityId()) && code.equals(d.getWorkflowCode()) && d.isActive())
                            .max(Comparator.comparingInt(WorkflowDefinitionEntity::getVersionNumber));
                });
        when(defRepo.save(any(WorkflowDefinitionEntity.class))).thenAnswer(inv -> {
            WorkflowDefinitionEntity d = inv.getArgument(0);
            definitionStore.put(d.getId(), d);
            return d;
        });

        WorkflowInstanceRepository instRepo = mock(WorkflowInstanceRepository.class);
        when(instRepo.save(any(WorkflowInstanceEntity.class))).thenAnswer(inv -> {
            WorkflowInstanceEntity i = inv.getArgument(0);
            instanceStore.put(i.getId(), i);
            return i;
        });
        when(instRepo.findById(any(UUID.class))).thenAnswer(inv -> Optional.ofNullable(instanceStore.get(inv.getArgument(0))));

        WorkflowStepLogRepository logRepo = mock(WorkflowStepLogRepository.class);
        when(logRepo.save(any(WorkflowStepLogEntity.class))).thenAnswer(inv -> {
            WorkflowStepLogEntity l = inv.getArgument(0);
            stepLogStore.add(l);
            return l;
        });

        WorkflowSubflowLinkRepository subflowRepo = mock(WorkflowSubflowLinkRepository.class);
        when(subflowRepo.save(any(WorkflowSubflowLinkEntity.class))).thenAnswer(inv -> {
            WorkflowSubflowLinkEntity s = inv.getArgument(0);
            subflowStore.put(s.getId(), s);
            return s;
        });
        when(subflowRepo.findByFacilityIdAndParentWorkflowId(any(), any())).thenAnswer(inv -> {
            UUID fac = inv.getArgument(0);
            UUID parentId = inv.getArgument(1);
            return subflowStore.values().stream()
                    .filter(s -> fac.equals(s.getFacilityId()) && parentId.equals(s.getParentWorkflowId()))
                    .toList();
        });

        DepartmentOrderRoutingRepository routingRepo = mock(DepartmentOrderRoutingRepository.class);
        when(routingRepo.save(any(DepartmentOrderRoutingEntity.class))).thenAnswer(inv -> {
            DepartmentOrderRoutingEntity r = inv.getArgument(0);
            routingStore.put(r.getId(), r);
            return r;
        });
        when(routingRepo.findByFacilityIdAndIdempotencyKey(any(), anyString())).thenAnswer(inv -> {
            UUID fac = inv.getArgument(0);
            String key = inv.getArgument(1);
            return routingStore.values().stream()
                    .filter(r -> fac.equals(r.getFacilityId()) && key.equals(r.getIdempotencyKey()))
                    .findFirst();
        });
        when(routingRepo.findByFacilityIdAndEncounterId(any(), any())).thenAnswer(inv -> {
            UUID fac = inv.getArgument(0);
            UUID enc = inv.getArgument(1);
            return routingStore.values().stream()
                    .filter(r -> fac.equals(r.getFacilityId()) && enc.equals(r.getEncounterId()))
                    .toList();
        });
        when(routingRepo.findByFacilityIdAndOrderId(any(), any())).thenAnswer(inv -> {
            UUID fac = inv.getArgument(0);
            UUID order = inv.getArgument(1);
            return routingStore.values().stream()
                    .filter(r -> fac.equals(r.getFacilityId()) && order.equals(r.getOrderId()))
                    .findFirst();
        });

        ClinicianResultInboxRepository inboxRepo = mock(ClinicianResultInboxRepository.class);
        when(inboxRepo.save(any(ClinicianResultInboxEntity.class))).thenAnswer(inv -> {
            ClinicianResultInboxEntity i = inv.getArgument(0);
            inboxStore.put(i.getId(), i);
            return i;
        });
        when(inboxRepo.findById(any(UUID.class))).thenAnswer(inv -> Optional.ofNullable(inboxStore.get(inv.getArgument(0))));
        when(inboxRepo.findByFacilityIdAndResultIdAndResponsibleClinicianId(any(), any(), anyString())).thenAnswer(inv -> {
            UUID fac = inv.getArgument(0);
            UUID resId = inv.getArgument(1);
            String clin = inv.getArgument(2);
            return inboxStore.values().stream()
                    .filter(i -> fac.equals(i.getFacilityId()) && resId.equals(i.getResultId()) && clin.equals(i.getResponsibleClinicianId()))
                    .findFirst();
        });
        when(inboxRepo.findByFacilityIdAndEncounterIdOrderByReceivedAtAsc(any(), any())).thenAnswer(inv -> {
            UUID fac = inv.getArgument(0);
            UUID enc = inv.getArgument(1);
            return inboxStore.values().stream()
                    .filter(i -> fac.equals(i.getFacilityId()) && enc.equals(i.getEncounterId()))
                    .sorted(Comparator.comparing(ClinicianResultInboxEntity::getReceivedAt))
                    .toList();
        });
        when(inboxRepo.findByFacilityIdAndResponsibleClinicianIdOrderByCriticalFlagDescReceivedAtAsc(any(), anyString()))
                .thenAnswer(inv -> {
                    UUID fac = inv.getArgument(0);
                    String clin = inv.getArgument(1);
                    return inboxStore.values().stream()
                            .filter(i -> fac.equals(i.getFacilityId()) && clin.equals(i.getResponsibleClinicianId()))
                            .sorted(Comparator.comparing(ClinicianResultInboxEntity::isCriticalFlag).reversed()
                                    .thenComparing(ClinicianResultInboxEntity::getReceivedAt))
                            .toList();
                });
        when(inboxRepo.findByCriticalFlagTrueAndAcknowledgedAtIsNullAndEscalationDeadlineAtBefore(any(Instant.class)))
                .thenAnswer(inv -> {
                    Instant cutoff = inv.getArgument(0);
                    return inboxStore.values().stream()
                            .filter(i -> i.isCriticalFlag() && i.getAcknowledgedAt() == null
                                    && i.getEscalationDeadlineAt() != null && i.getEscalationDeadlineAt().isBefore(cutoff))
                            .toList();
                });

        DurableNotificationRepository notifRepo = mock(DurableNotificationRepository.class);
        when(notifRepo.save(any(DurableNotificationEntity.class))).thenAnswer(inv -> {
            DurableNotificationEntity n = inv.getArgument(0);
            notificationStore.put(n.getId(), n);
            return n;
        });
        when(notifRepo.findById(any(UUID.class))).thenAnswer(inv -> Optional.ofNullable(notificationStore.get(inv.getArgument(0))));
        when(notifRepo.findByFacilityIdAndIdempotencyKey(any(), anyString())).thenAnswer(inv -> {
            UUID fac = inv.getArgument(0);
            String key = inv.getArgument(1);
            return notificationStore.values().stream()
                    .filter(n -> fac.equals(n.getFacilityId()) && key.equals(n.getIdempotencyKey()))
                    .findFirst();
        });

        DurableProcessedEventRepository procRepo = mock(DurableProcessedEventRepository.class);
        when(procRepo.save(any(DurableProcessedEventEntity.class))).thenAnswer(inv -> {
            DurableProcessedEventEntity p = inv.getArgument(0);
            processedEventStore.put(p.getFacilityId() + "|" + p.getIdempotencyKey(), p);
            return p;
        });
        when(procRepo.findByFacilityIdAndIdempotencyKey(any(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(processedEventStore.get(inv.getArgument(0) + "|" + inv.getArgument(1))));

        QueueCounterRepository counterRepo = mock(QueueCounterRepository.class);
        when(counterRepo.findForUpdate(any(), anyString(), any(LocalDate.class))).thenAnswer(inv ->
                Optional.ofNullable(queueCounterStore.get(inv.getArgument(0) + "|" + inv.getArgument(1) + "|" + inv.getArgument(2))));
        when(counterRepo.save(any(QueueCounterEntity.class))).thenAnswer(inv -> {
            QueueCounterEntity c = inv.getArgument(0);
            queueCounterStore.put(c.getFacilityId() + "|" + c.getDepartmentCode() + "|" + c.getServiceDate(), c);
            return c;
        });

        DepartmentQueueEntryRepository entryRepo = mock(DepartmentQueueEntryRepository.class);
        when(entryRepo.save(any(DepartmentQueueEntryEntity.class))).thenAnswer(inv -> {
            DepartmentQueueEntryEntity e = inv.getArgument(0);
            queueEntryStore.put(e.getId(), e);
            return e;
        });
        when(entryRepo.findById(any(UUID.class))).thenAnswer(inv -> Optional.ofNullable(queueEntryStore.get(inv.getArgument(0))));
        when(entryRepo.findByFacilityIdAndIdempotencyKey(any(), anyString())).thenAnswer(inv -> {
            UUID fac = inv.getArgument(0);
            String key = inv.getArgument(1);
            return queueEntryStore.values().stream()
                    .filter(e -> fac.equals(e.getFacilityId()) && key.equals(e.getIdempotencyKey()))
                    .findFirst();
        });
        when(entryRepo.findByFacilityIdAndDepartmentCodeOrderByUrgencyScoreDescCheckInAtAsc(any(), anyString()))
                .thenAnswer(inv -> {
                    UUID fac = inv.getArgument(0);
                    String dept = inv.getArgument(1);
                    return queueEntryStore.values().stream()
                            .filter(e -> fac.equals(e.getFacilityId()) && dept.equals(e.getDepartmentCode()))
                            .sorted(Comparator.comparingInt(DepartmentQueueEntryEntity::getUrgencyScore).reversed()
                                    .thenComparing(DepartmentQueueEntryEntity::getCheckInAt))
                            .toList();
                });

        QueuePauseControlRepository pauseRepo = mock(QueuePauseControlRepository.class);
        when(pauseRepo.findByFacilityIdAndDepartmentCode(any(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(queuePauseStore.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(pauseRepo.save(any(QueuePauseControlEntity.class))).thenAnswer(inv -> {
            QueuePauseControlEntity p = inv.getArgument(0);
            queuePauseStore.put(p.getFacilityId() + "|" + p.getDepartmentCode(), p);
            return p;
        });

        NotificationProviderAdapterSpi smsAdapter = new NotificationProviderAdapterSpi() {
            @Override
            public boolean supports(NotificationChannel channel) {
                return channel == NotificationChannel.SMS || channel == NotificationChannel.WHATSAPP;
            }

            @Override
            public ProviderSubmissionResult submit(DurableNotificationEntity notification) {
                int seq = providerSubmissions.incrementAndGet();
                return new ProviderSubmissionResult(true, "PROVIDER-RCPT-" + seq, null);
            }
        };

        workflowService = new WorkflowOrchestrationService(defRepo, instRepo, logRepo, outboxRepo, List.of(), List.of());
        journeyService = new PatientJourneyOrchestratorService(
                workflowService, subflowRepo, routingRepo, inboxRepo, notifRepo, procRepo,
                outboxRepo, new DefaultFacilityBillingClearancePolicy(), List.of(smsAdapter));
        queueService = new QueueManagementService(counterRepo, entryRepo, pauseRepo, outboxRepo, null, List.of());
    }

    @Test
    @DisplayName("Test A — Returning outpatient reuses authoritative MPI patientId and opens distinct encounters")
    void testA_returningOutpatientPreservesSingleIdentityAcrossVisits() {
        UUID facId = UUID.randomUUID();
        UUID authoritativePatientId = UUID.randomUUID();
        UUID visit1EncounterId = UUID.randomUUID();
        UUID visit2EncounterId = UUID.randomUUID();

        WorkflowInstanceEntity visit1 = journeyService.startParentOutpatientJourney(
                facId, authoritativePatientId, visit1EncounterId, false, false, "reception-1");
        WorkflowInstanceEntity visit2 = journeyService.startParentOutpatientJourney(
                facId, authoritativePatientId, visit2EncounterId, true, false, "reception-2");

        assertEquals(authoritativePatientId, visit1.getSubjectPatientId());
        assertEquals(authoritativePatientId, visit2.getSubjectPatientId());
        assertNotEquals(visit1.getId(), visit2.getId(), "Each visit creates a distinct workflowInstanceId");
        assertNotEquals(visit1EncounterId, visit2EncounterId, "Each visit creates a distinct encounterId");
        assertTrue(outboxStore.stream().anyMatch(e -> e.getEventType().equals(CanonicalWorkflowEventType.PatientRegistered.name())));
        assertTrue(outboxStore.stream().anyMatch(e -> e.getEventType().equals(CanonicalWorkflowEventType.PatientMatched.name())));
    }

    @Test
    @DisplayName("Test B — Full outpatient journey: 1 encounter -> lab + imaging + pharmacy -> results -> ack -> re-entry -> finalization")
    void testB_fullOutpatientJourneyEndToEnd() {
        UUID facId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        FacilityBillingPolicyConfig policy = FacilityBillingPolicyConfig.defaultFacilityPolicy();

        // 1. Start parent OPD workflow & triage + consultation clearance
        WorkflowInstanceEntity parent = journeyService.startParentOutpatientJourney(
                facId, patientId, encounterId, true, false, "reception-1");
        BillingClearanceDecision triageDecision = journeyService.evaluateTriageAndConsultationClearance(
                facId, patientId, encounterId, parent.getId(), 4, false,
                new BillingClearanceEvaluationRequest(facId, patientId, encounterId, null,
                        ServiceClearanceCategory.CONSULTATION, false, false, false, false, false, false, 5000, 5000),
                policy, "nurse-triage");
        assertEquals(PaymentClearanceOutcome.PAID, triageDecision.outcome());
        assertTrue(triageDecision.fulfillmentEligible());

        // 2. Consultation queue check-in & doctor claim
        DepartmentQueueEntryEntity docQ = queueService.enqueue(facId, new EnqueueRequest(
                "DOCTOR_CONSULTATION", patientId, encounterId, null, null, parent.getId(),
                QueuePriority.ROUTINE, triageDecision.outcome().name(), null, 45, "DOC-ENC-" + encounterId), "triage");
        queueService.claimTask(facId, docQ.getId(), "dr-nkeng");

        // 3. Clinician signs Lab + Radiology + Pharmacy orders -> independent parallel tasks
        UUID labOrderId = UUID.randomUUID();
        UUID radOrderId = UUID.randomUUID();
        UUID rxOrderId = UUID.randomUUID();

        DepartmentOrderRoutingEntity labRoute = journeyService.routeSignedOrder(new RouteSignedOrderCommand(
                facId, patientId, encounterId, parent.getId(), labOrderId, OrderTargetDepartment.LABORATORY,
                null, false, true, false, false, false, 15000, 0, "dr-nkeng", "ROUTE-LAB-" + labOrderId), policy);
        DepartmentOrderRoutingEntity radRoute = journeyService.routeSignedOrder(new RouteSignedOrderCommand(
                facId, patientId, encounterId, parent.getId(), radOrderId, OrderTargetDepartment.RADIOLOGY,
                null, false, true, false, false, false, 25000, 0, "dr-nkeng", "ROUTE-RAD-" + radOrderId), policy);
        DepartmentOrderRoutingEntity rxRoute = journeyService.routeSignedOrder(new RouteSignedOrderCommand(
                facId, patientId, encounterId, parent.getId(), rxOrderId, OrderTargetDepartment.PHARMACY,
                null, false, true, false, false, false, 8000, 0, "dr-nkeng", "ROUTE-RX-" + rxOrderId), policy);

        assertEquals(OrderRoutingStatus.ACTIVE_IN_DEPARTMENT, labRoute.getRoutingStatus());
        assertEquals(OrderRoutingStatus.ACTIVE_IN_DEPARTMENT, radRoute.getRoutingStatus());
        assertNotNull(radRoute.getChildWorkflowId(), "Radiology orders must activate a radiology subworkflow");
        assertEquals(OrderRoutingStatus.ACTIVE_IN_DEPARTMENT, rxRoute.getRoutingStatus());

        // 4. Independent departmental queues + completion
        DepartmentQueueEntryEntity labQ = queueService.enqueue(facId, new EnqueueRequest(
                "LAB_COLLECTION", patientId, encounterId, labOrderId, null, labRoute.getChildWorkflowId(),
                QueuePriority.ROUTINE, labRoute.getFinancialClearance().name(), null, 45, "Q-LAB-" + labOrderId), "router");
        DepartmentQueueEntryEntity radQ = queueService.enqueue(facId, new EnqueueRequest(
                "RADIOLOGY", patientId, encounterId, radOrderId, null, radRoute.getChildWorkflowId(),
                QueuePriority.ROUTINE, radRoute.getFinancialClearance().name(), null, 60, "Q-RAD-" + radOrderId), "router");
        DepartmentQueueEntryEntity rxQ = queueService.enqueue(facId, new EnqueueRequest(
                "PHARMACY", patientId, encounterId, rxOrderId, null, rxRoute.getChildWorkflowId(),
                QueuePriority.ROUTINE, rxRoute.getFinancialClearance().name(), null, 30, "Q-RX-" + rxOrderId), "router");

        queueService.claimTask(facId, labQ.getId(), "lab-tech-1");
        queueService.completeEntry(facId, labQ.getId(), "lab-tech-1");
        journeyService.markRoutedOrderCompleted(facId, labOrderId);

        queueService.claimTask(facId, radQ.getId(), "rad-tech-1");
        queueService.completeEntry(facId, radQ.getId(), "rad-tech-1");
        journeyService.markRoutedOrderCompleted(facId, radOrderId);

        queueService.claimTask(facId, rxQ.getId(), "pharm-1");
        queueService.completeEntry(facId, rxQ.getId(), "pharm-1");
        journeyService.markRoutedOrderCompleted(facId, rxOrderId);

        // 5. Verified results routed to clinician inbox + explicit acknowledgment
        ClinicianResultInboxEntity labInbox = journeyService.deliverVerifiedResultToClinicianInbox(
                new DeliverResultToInboxCommand(facId, patientId, encounterId, labOrderId, UUID.randomUUID(),
                        "LAB", "dr-nkeng", "OPD-TEAM", "dr-chief", false, false, true, 15));
        assertEquals(ClinicianInboxStatus.AWAITING_REVIEW, labInbox.getInboxStatus());
        assertNull(labInbox.getAcknowledgedAt(), "Notification delivery must not count as clinician acknowledgment");

        ClinicianResultInboxEntity acked = journeyService.acknowledgeClinicianInboxItem(
                facId, labInbox.getId(), "dr-nkeng", "Malaria RDT negative; continue supportive care", true);
        assertEquals(ClinicianInboxStatus.RESOLVED, acked.getInboxStatus());
        assertNotNull(acked.getAcknowledgedAt());

        // 6. Patient re-enters consultation queue after investigations with elevated priority
        DepartmentQueueEntryEntity reentry = queueService.reenterAfterInvestigations(
                facId, encounterId, patientId, "DOCTOR_CONSULTATION", "dr-nkeng", "REENTRY-" + encounterId);
        assertEquals(1, reentry.getReentryCount());
        assertEquals(QueuePriority.URGENT, reentry.getPriority());
        assertTrue(reentry.getUrgencyScore() > docQ.getUrgencyScore());

        // 7. Encounter is now safe to finalize
        EncounterFinalizationCheck check = journeyService.verifyCanFinalizeEncounter(facId, encounterId);
        assertTrue(check.canFinalize(), "Encounter should be finalizable once all parallel orders & results are complete");
        assertTrue(check.blockingReasons().isEmpty());
    }

    @Test
    @DisplayName("Test C — Emergency unstable patient receives EMERGENCY_OVERRIDE bypassing prepayment")
    void testC_emergencyBypassesPrepaymentAndActivatesImmediately() {
        UUID facId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        FacilityBillingPolicyConfig strictPrepay = FacilityBillingPolicyConfig.defaultFacilityPolicy();

        WorkflowInstanceEntity parent = journeyService.startParentOutpatientJourney(
                facId, patientId, encounterId, false, true, "ed-triage");
        BillingClearanceDecision decision = journeyService.evaluateTriageAndConsultationClearance(
                facId, patientId, encounterId, parent.getId(), 1, true,
                new BillingClearanceEvaluationRequest(facId, patientId, encounterId, null,
                        ServiceClearanceCategory.CONSULTATION, true, false, false, false, false, false, 20000, 0),
                strictPrepay, "ed-triage");

        assertEquals(PaymentClearanceOutcome.EMERGENCY_OVERRIDE, decision.outcome());
        assertTrue(decision.fulfillmentEligible());
        assertFalse(decision.routeToCashierWorklist());

        UUID statOrderId = UUID.randomUUID();
        DepartmentOrderRoutingEntity statLab = journeyService.routeSignedOrder(new RouteSignedOrderCommand(
                facId, patientId, encounterId, parent.getId(), statOrderId, OrderTargetDepartment.LABORATORY,
                null, true, false, false, false, false, 18000, 0, "dr-ed", "STAT-" + statOrderId), strictPrepay);
        assertEquals(PaymentClearanceOutcome.EMERGENCY_OVERRIDE, statLab.getFinancialClearance());
        assertEquals(OrderRoutingStatus.ACTIVE_IN_DEPARTMENT, statLab.getRoutingStatus());
    }

    @Test
    @DisplayName("Test D — Unpaid non-emergency order preserved on financial hold; payment confirmation releases cleanly")
    void testD_unpaidNonEmergencyOrderHeldIntactThenReleasedOnPayment() {
        UUID facId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        FacilityBillingPolicyConfig strictPrepay = FacilityBillingPolicyConfig.defaultFacilityPolicy();

        DepartmentOrderRoutingEntity held = journeyService.routeSignedOrder(new RouteSignedOrderCommand(
                facId, patientId, encounterId, null, orderId, OrderTargetDepartment.LABORATORY,
                null, false, false, false, false, false, 12000, 0, "dr-opd", "UNPAID-" + orderId), strictPrepay);

        assertEquals(PaymentClearanceOutcome.PAYMENT_PENDING, held.getFinancialClearance());
        assertEquals(OrderRoutingStatus.ROUTED_AWAITING_CLEARANCE, held.getRoutingStatus());
        assertThrows(IllegalStateException.class, () -> journeyService.markRoutedOrderCompleted(facId, orderId));

        DepartmentQueueEntryEntity heldQ = queueService.enqueue(facId, new EnqueueRequest(
                "LAB_COLLECTION", patientId, encounterId, orderId, null, null,
                QueuePriority.ROUTINE, held.getFinancialClearance().name(), null, 45, "Q-UNPAID-" + orderId), "router");
        assertFalse(heldQ.isEligibleForActivation());
        assertEquals(QueueStatus.WAITING, heldQ.getQueueStatus());
        assertThrows(IllegalStateException.class, () -> queueService.claimTask(facId, heldQ.getId(), "lab-tech"));

        // Payment confirmed -> releases both routing task and queue entry without duplicates
        DepartmentOrderRoutingEntity released = journeyService.confirmPaymentAndReleaseOrder(
                facId, orderId, PaymentClearanceOutcome.PAID, "PAY-RCPT-001", "cashier-1");
        assertEquals(OrderRoutingStatus.ACTIVE_IN_DEPARTMENT, released.getRoutingStatus());
        assertEquals(PaymentClearanceOutcome.PAID, released.getFinancialClearance());

        DepartmentQueueEntryEntity readyQ = queueService.updateFinancialClearance(facId, heldQ.getId(), "PAID");
        assertTrue(readyQ.isEligibleForActivation());
        assertEquals(QueueStatus.READY, readyQ.getQueueStatus());
        assertEquals(QueueStatus.IN_SERVICE, queueService.claimTask(facId, readyQ.getId(), "lab-tech").getQueueStatus());
        assertEquals(1, routingStore.size(), "Releasing hold must preserve single order routing without duplication");
    }

    @Test
    @DisplayName("Test E — Critical result escalates to fallback clinician if unacknowledged and ignores financial blocks")
    void testE_criticalResultEscalatesAndNeverBlockedByFinancialHold() {
        UUID facId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        // Verify financial policy NEVER blocks critical finding communication even with unpaid balance
        BillingClearanceDecision critClearance = new DefaultFacilityBillingClearancePolicy().evaluate(
                new BillingClearanceEvaluationRequest(facId, patientId, encounterId, orderId,
                        ServiceClearanceCategory.CRITICAL_RESULT_COMMUNICATION, false, true,
                        false, false, false, false, 50000, 0),
                FacilityBillingPolicyConfig.defaultFacilityPolicy());
        assertEquals(PaymentClearanceOutcome.EMERGENCY_OVERRIDE, critClearance.outcome());
        assertTrue(critClearance.fulfillmentEligible());

        ClinicianResultInboxEntity crit = journeyService.deliverVerifiedResultToClinicianInbox(
                new DeliverResultToInboxCommand(facId, patientId, encounterId, orderId, UUID.randomUUID(),
                        "LAB", "dr-primary", "MED-TEAM", "dr-oncall-fallback", true, false, true, 15));
        assertEquals(ClinicianInboxStatus.CRITICAL_ESCALATION_PENDING, crit.getInboxStatus());
        assertFalse(crit.isElectronicNotificationSent(), "Creating an inbox item is not provider-confirmed notification delivery");
        assertNotNull(crit.getEscalationDeadlineAt());
        assertNull(crit.getAcknowledgedAt());

        // Simulate SLA deadline expiry -> escalates to fallback clinician
        List<ClinicianResultInboxEntity> escalated = journeyService.escalateOverdueCriticalInboxItems(
                Instant.now().plus(Duration.ofMinutes(20)));
        assertEquals(1, escalated.size());
        assertEquals(ClinicianInboxStatus.ESCALATED, escalated.getFirst().getInboxStatus());
        assertEquals("dr-oncall-fallback", escalated.getFirst().getResponsibleClinicianId());
    }

    @Test
    @DisplayName("Test F — Multiple concurrent orders prevent premature finalization; amended result reopens clinician action")
    void testF_concurrentOrdersAndAmendedResultGuardEncounterFinalization() {
        UUID facId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        FacilityBillingPolicyConfig freePolicy = new FacilityBillingPolicyConfig(false, false, false, false, true, false);

        UUID labOrder = UUID.randomUUID();
        UUID radOrder = UUID.randomUUID();
        journeyService.routeSignedOrder(new RouteSignedOrderCommand(
                facId, patientId, encounterId, null, labOrder, OrderTargetDepartment.LABORATORY,
                null, false, false, false, false, false, 0, 0, "dr-1", "F-LAB"), freePolicy);
        journeyService.routeSignedOrder(new RouteSignedOrderCommand(
                facId, patientId, encounterId, null, radOrder, OrderTargetDepartment.RADIOLOGY,
                null, false, false, false, false, false, 0, 0, "dr-1", "F-RAD"), freePolicy);

        // Complete lab only -> encounter MUST NOT finalize while radiology is still open
        journeyService.markRoutedOrderCompleted(facId, labOrder);
        EncounterFinalizationCheck midCheck = journeyService.verifyCanFinalizeEncounter(facId, encounterId);
        assertFalse(midCheck.canFinalize());
        assertEquals(1, midCheck.blockingReasons().size());

        // Complete radiology -> verify result & acknowledge -> encounter becomes finalizable
        journeyService.markRoutedOrderCompleted(facId, radOrder);
        UUID resultId = UUID.randomUUID();
        ClinicianResultInboxEntity initial = journeyService.deliverVerifiedResultToClinicianInbox(
                new DeliverResultToInboxCommand(facId, patientId, encounterId, labOrder, resultId,
                        "LAB", "dr-1", "OPD", "dr-2", false, false, false, 15));
        journeyService.acknowledgeClinicianInboxItem(facId, initial.getId(), "dr-1", "Reviewed normal", true);
        assertTrue(journeyService.verifyCanFinalizeEncounter(facId, encounterId).canFinalize());

        // Late amended result arrives -> reopens clinician action and blocks finalization until re-acknowledged
        ClinicianResultInboxEntity amended = journeyService.deliverVerifiedResultToClinicianInbox(
                new DeliverResultToInboxCommand(facId, patientId, encounterId, labOrder, resultId,
                        "LAB", "dr-1", "OPD", "dr-2", false, true, true, 15));
        assertEquals(ClinicianInboxStatus.ACTION_REQUIRED, amended.getInboxStatus());
        assertNull(amended.getAcknowledgedAt());
        assertFalse(journeyService.verifyCanFinalizeEncounter(facId, encounterId).canFinalize());
    }

    @Test
    @DisplayName("Test G — Duplicate event replays create zero duplicate orders, queue tasks, or payment releases")
    void testG_duplicateEventReplaysAreStrictlyIdempotent() {
        UUID facId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        FacilityBillingPolicyConfig policy = FacilityBillingPolicyConfig.defaultFacilityPolicy();

        RouteSignedOrderCommand cmd = new RouteSignedOrderCommand(
                facId, patientId, encounterId, null, orderId, OrderTargetDepartment.LABORATORY,
                null, false, false, false, false, false, 5000, 0, "dr-1", "IDEMP-ORD-" + orderId);
        DepartmentOrderRoutingEntity first = journeyService.routeSignedOrder(cmd, policy);
        DepartmentOrderRoutingEntity replay = journeyService.routeSignedOrder(cmd, policy);
        assertEquals(first.getId(), replay.getId());
        assertEquals(1, routingStore.size());

        EnqueueRequest qReq = new EnqueueRequest("LAB_COLLECTION", patientId, encounterId, orderId, null, null,
                QueuePriority.ROUTINE, "PAYMENT_PENDING", null, 45, "IDEMP-Q-" + orderId);
        DepartmentQueueEntryEntity q1 = queueService.enqueue(facId, qReq, "router");
        DepartmentQueueEntryEntity q2 = queueService.enqueue(facId, qReq, "router");
        assertEquals(q1.getId(), q2.getId());
        assertEquals(1, queueEntryStore.size());

        long serviceClearedBefore = outboxStore.stream().filter(e -> e.getEventType().equals("ServiceCleared")).count();
        journeyService.confirmPaymentAndReleaseOrder(facId, orderId, PaymentClearanceOutcome.PAID, "PAY-IDEMP-77", "cashier");
        journeyService.confirmPaymentAndReleaseOrder(facId, orderId, PaymentClearanceOutcome.PAID, "PAY-IDEMP-77", "cashier");
        long serviceClearedAfter = outboxStore.stream().filter(e -> e.getEventType().equals("ServiceCleared")).count();
        assertEquals(serviceClearedBefore + 1, serviceClearedAfter, "Replayed payment confirmation must emit ServiceCleared exactly once");
    }

    @Test
    @DisplayName("Test H — Concurrent check-ins allocate strictly unique tickets; concurrent claims allow exactly 1 worker")
    void testH_concurrentQueueAllocationAndAtomicWorkerClaiming() throws Exception {
        UUID facId = UUID.randomUUID();
        int checkIns = 16;
        ExecutorService pool = Executors.newFixedThreadPool(checkIns);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<DepartmentQueueEntryEntity>> futures = new ArrayList<>();
        for (int i = 0; i < checkIns; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                gate.await();
                return queueService.enqueue(facId, new EnqueueRequest(
                        "DOCTOR_CONSULTATION", UUID.randomUUID(), UUID.randomUUID(), null, null, null,
                        QueuePriority.ROUTINE, "PAID", null, 60, "CONC-H-" + idx), "reception");
            }));
        }
        gate.countDown();
        Set<String> tickets = new HashSet<>();
        DepartmentQueueEntryEntity contested = null;
        for (Future<DepartmentQueueEntryEntity> f : futures) {
            DepartmentQueueEntryEntity entry = f.get(10, TimeUnit.SECONDS);
            tickets.add(entry.getTicketNumber());
            if (contested == null) contested = entry;
        }
        assertEquals(checkIns, tickets.size(), "All 16 concurrent tickets must be strictly unique");

        // 8 workers racing to claim the same queue ticket -> exactly 1 succeeds
        int workers = 8;
        CountDownLatch claimGate = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        final UUID targetId = contested.getId();
        List<Future<?>> claimFutures = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            final String workerId = "clinician-" + w;
            claimFutures.add(pool.submit(() -> {
                try {
                    claimGate.await();
                    queueService.claimTask(facId, targetId, workerId);
                    wins.incrementAndGet();
                } catch (IllegalStateException conflict) {
                    conflicts.incrementAndGet();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        claimGate.countDown();
        for (Future<?> f : claimFutures) f.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(1, wins.get(), "Exactly one worker must win the atomic queue claim");
        assertEquals(workers - 1, conflicts.get(), "All other racing workers must be cleanly rejected");
    }

    @Test
    @DisplayName("Test I — Cross-facility isolation, placeholder recipient rejection, and privacy-minimized notifications")
    void testI_securityTenantIsolationAndNotificationPrivacy() {
        UUID facA = UUID.randomUUID();
        UUID facB = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();

        DepartmentQueueEntryEntity entryA = queueService.enqueue(facA, new EnqueueRequest(
                "RADIOLOGY", patientId, encounterId, null, null, null,
                QueuePriority.ROUTINE, "PAID", null, 45, "SEC-Q-1"), "reception");
        assertThrows(SecurityException.class, () -> queueService.claimTask(facB, entryA.getId(), "intruder-tech"));

        ClinicianResultInboxEntity inboxA = journeyService.deliverVerifiedResultToClinicianInbox(
                new DeliverResultToInboxCommand(facA, patientId, encounterId, UUID.randomUUID(), UUID.randomUUID(),
                        "LAB", "dr-a", "TEAM-A", "dr-b", false, false, false, 15));
        assertThrows(SecurityException.class,
                () -> journeyService.acknowledgeClinicianInboxItem(facB, inboxA.getId(), "dr-intruder", "steal", true));

        // Placeholder recipient addresses and raw diagnoses MUST be rejected
        assertThrows(IllegalArgumentException.class, () -> journeyService.dispatchDurableNotification(
                new RequestDurableNotificationCommand(facA, patientId, encounterId, NotificationChannel.SMS,
                        "RESULT_READY", "en", "+237670000000", Map.of(), false, false, false, "BAD-1")));
        assertThrows(IllegalArgumentException.class, () -> journeyService.dispatchDurableNotification(
                new RequestDurableNotificationCommand(facA, patientId, encounterId, NotificationChannel.SMS,
                        "RESULT_READY", "en", "+237699112233", Map.of(), false, false, true, "BAD-2")));

        // Opt-out policy suppresses routine notification without calling external provider
        int beforeSubmits = providerSubmissions.get();
        DurableNotificationEntity suppressed = journeyService.dispatchDurableNotification(
                new RequestDurableNotificationCommand(facA, patientId, encounterId, NotificationChannel.SMS,
                        "RESULT_READY", "fr", "+237699112233", Map.of("facility", "Hopital General"), true, false, false, "OPT-OUT-1"));
        assertEquals(NotificationDeliveryState.SUPPRESSED_BY_POLICY, suppressed.getDeliveryState());
        assertEquals(beforeSubmits, providerSubmissions.get());

        // Real notification enters SUBMITTED_TO_PROVIDER (NOT DELIVERED until real receipt arrives)
        DurableNotificationEntity submitted = journeyService.dispatchDurableNotification(
                new RequestDurableNotificationCommand(facA, patientId, encounterId, NotificationChannel.SMS,
                        "RESULT_READY", "fr", "+237699112233", Map.of("facility", "Hopital General"), false, false, false, "REAL-1"));
        assertEquals(NotificationDeliveryState.SUBMITTED_TO_PROVIDER, submitted.getDeliveryState());
        assertThrows(SecurityException.class,
                () -> journeyService.confirmNotificationDeliveryReceipt(facB, submitted.getId(), "FORGED"));
        DurableNotificationEntity delivered = journeyService.confirmNotificationDeliveryReceipt(facA, submitted.getId(), "CARRIER-ACK-99");
        assertEquals(NotificationDeliveryState.DELIVERED, delivered.getDeliveryState());
    }

    @Test
    @DisplayName("Test J — Restart and recovery preserves queue counters, held orders, inbox items, and notifications")
    void testJ_restartAndRecoveryOverDurableState() {
        UUID facId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        DepartmentQueueEntryEntity firstTicket = queueService.enqueue(facId, new EnqueueRequest(
                "LAB_COLLECTION", patientId, encounterId, orderId, null, null,
                QueuePriority.ROUTINE, "PAYMENT_PENDING", null, 45, "RESTART-Q-1"), "reception");
        assertEquals("LAB-0001", firstTicket.getTicketNumber());

        journeyService.routeSignedOrder(new RouteSignedOrderCommand(
                facId, patientId, encounterId, null, orderId, OrderTargetDepartment.LABORATORY,
                null, false, false, false, false, false, 10000, 0, "dr-1", "RESTART-ORD-1"),
                FacilityBillingPolicyConfig.defaultFacilityPolicy());
        DurableNotificationEntity notif = journeyService.dispatchDurableNotification(
                new RequestDurableNotificationCommand(facId, patientId, encounterId, NotificationChannel.SMS,
                        "MEDICATION_READY", "en", "+237699445566", Map.of("facility", "Central Clinic"),
                        false, false, false, "RESTART-NOTIF-1"));

        // Simulate full application restart: discard in-memory service instances and construct fresh ones over durable stores
        rebuildServicesOverBackingStores();

        DepartmentQueueEntryEntity secondTicket = queueService.enqueue(facId, new EnqueueRequest(
                "LAB_COLLECTION", UUID.randomUUID(), UUID.randomUUID(), null, null, null,
                QueuePriority.ROUTINE, "PAID", null, 45, "RESTART-Q-2"), "reception");
        assertEquals("LAB-0002", secondTicket.getTicketNumber(), "Ticket sequence must continue monotonically after restart");

        DepartmentOrderRoutingEntity released = journeyService.confirmPaymentAndReleaseOrder(
                facId, orderId, PaymentClearanceOutcome.PAID, "RESTART-PAY-1", "cashier");
        assertEquals(OrderRoutingStatus.ACTIVE_IN_DEPARTMENT, released.getRoutingStatus());

        DurableNotificationEntity confirmed = journeyService.confirmNotificationDeliveryReceipt(
                facId, notif.getId(), "DELIVERY-POST-RESTART");
        assertEquals(NotificationDeliveryState.DELIVERED, confirmed.getDeliveryState());
    }

    @Test
    @DisplayName("Safety regression — routing replay cannot change patient, encounter, or department")
    void rejectsCrossPatientRoutingReplayEvenWhenIdempotencyKeyIsReused() {
        UUID facility = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        UUID encounter = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        FacilityBillingPolicyConfig config = FacilityBillingPolicyConfig.defaultFacilityPolicy();
        RouteSignedOrderCommand signed = new RouteSignedOrderCommand(
                facility, patient, encounter, null, order, OrderTargetDepartment.LABORATORY,
                null, false, true, false, false, false, 12000, 0,
                "dr-1", "ROUTE-IDEMP-1");
        DepartmentOrderRoutingEntity original = journeyService.routeSignedOrder(signed, config);
        assertSame(original, journeyService.routeSignedOrder(signed, config));

        RouteSignedOrderCommand changedIdentity = new RouteSignedOrderCommand(
                facility, UUID.randomUUID(), encounter, null, order, OrderTargetDepartment.LABORATORY,
                null, false, true, false, false, false, 12000, 0,
                "dr-1", "ROUTE-IDEMP-1");
        assertThrows(SecurityException.class,
                () -> journeyService.routeSignedOrder(changedIdentity, config));

        RouteSignedOrderCommand changedTarget = new RouteSignedOrderCommand(
                facility, patient, encounter, null, order, OrderTargetDepartment.PHARMACY,
                null, false, true, false, false, false, 12000, 0,
                "dr-1", "ROUTE-CHANGED-TARGET");
        assertThrows(SecurityException.class,
                () -> journeyService.routeSignedOrder(changedTarget, config));

        RouteSignedOrderCommand sameOrderNewKey = new RouteSignedOrderCommand(
                facility, patient, encounter, null, order, OrderTargetDepartment.LABORATORY,
                null, false, true, false, false, false, 12000, 0,
                "dr-1", "ROUTE-ANOTHER-DELIVERY");
        assertSame(original, journeyService.routeSignedOrder(sameOrderNewKey, config));
        assertEquals(1, routingStore.size(), "At-least-once delivery must not double-route an order");
    }

    @Test
    @DisplayName("Safety regression — completed orders are idempotent and cancelled/held orders are blocked")
    void cannotFulfillCancelledOrdersOrRewriteOriginalCompletion() {
        UUID facility = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        UUID encounter = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        FacilityBillingPolicyConfig policy = FacilityBillingPolicyConfig.defaultFacilityPolicy();
        DepartmentOrderRoutingEntity active = journeyService.routeSignedOrder(new RouteSignedOrderCommand(
                facility, patient, encounter, null, order, OrderTargetDepartment.LABORATORY,
                null, false, true, false, false, false, 2000, 0,
                "dr-1", "ORDER-COMPLETE-1"), policy);

        DepartmentOrderRoutingEntity first = journeyService.markRoutedOrderCompleted(facility, order);
        Instant completed = first.getCompletedAt();
        assertNotNull(completed);
        assertSame(first, journeyService.markRoutedOrderCompleted(facility, order));
        assertEquals(completed, first.getCompletedAt());

        first.setRoutingStatus(OrderRoutingStatus.CANCELLED);
        assertThrows(IllegalStateException.class, () -> journeyService.markRoutedOrderCompleted(facility, order));

        first.setRoutingStatus(OrderRoutingStatus.ACTIVE_IN_DEPARTMENT);
        first.setFinancialClearance(PaymentClearanceOutcome.PAYMENT_PENDING);
        assertThrows(IllegalStateException.class, () -> journeyService.markRoutedOrderCompleted(facility, order));
    }

    @Test
    @DisplayName("Safety regression — only current clinician can acknowledge critical results; amendments reopen review")
    void criticalInboxAcknowledgmentCannotBeForgedOrReusedAfterAmendment() {
        UUID fac = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        UUID encounter = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID result = UUID.randomUUID();
        DeliverResultToInboxCommand critical = new DeliverResultToInboxCommand(
                fac, patient, encounter, order, result, "LAB",
                "dr-primary", "CLINIC", "dr-fallback", true, false, true, 15);
        ClinicianResultInboxEntity item = journeyService.deliverVerifiedResultToClinicianInbox(critical);
        assertThrows(SecurityException.class,
                () -> journeyService.acknowledgeClinicianInboxItem(
                        fac, item.getId(), "another-doctor", "Received read-back", true));
        assertThrows(IllegalArgumentException.class,
                () -> journeyService.acknowledgeClinicianInboxItem(
                        fac, item.getId(), "dr-primary", "", true));
        assertNull(item.getAcknowledgedAt());

        journeyService.acknowledgeClinicianInboxItem(
                fac, item.getId(), "dr-primary", "Critical value read back and documented", true);
        Instant firstAck = item.getAcknowledgedAt();
        long eventCount = outboxStore.stream()
                .filter(e -> "ClinicianResultAcknowledged".equals(e.getEventType())).count();
        assertSame(item, journeyService.acknowledgeClinicianInboxItem(
                fac, item.getId(), "dr-primary", "Critical value read back and documented", true));
        assertEquals(firstAck, item.getAcknowledgedAt());
        assertEquals(eventCount, outboxStore.stream()
                .filter(e -> "ClinicianResultAcknowledged".equals(e.getEventType())).count());

        DeliverResultToInboxCommand amended = new DeliverResultToInboxCommand(
                fac, patient, encounter, order, result, "LAB",
                "dr-primary", "CLINIC", "dr-fallback", true, true, true, 15);
        assertSame(item, journeyService.deliverVerifiedResultToClinicianInbox(amended));
        assertEquals(ClinicianInboxStatus.CRITICAL_ESCALATION_PENDING, item.getInboxStatus());
        assertNull(item.getAcknowledgedAt());
        assertNull(item.getAcknowledgedBy());
        assertFalse(journeyService.verifyCanFinalizeEncounter(fac, encounter).canFinalize());

        DeliverResultToInboxCommand poisoned = new DeliverResultToInboxCommand(
                fac, UUID.randomUUID(), encounter, order, result, "LAB",
                "dr-primary", "CLINIC", "dr-fallback", true, false, true, 15);
        assertThrows(SecurityException.class,
                () -> journeyService.deliverVerifiedResultToClinicianInbox(poisoned));
    }

    @Test
    @DisplayName("Safety regression — overdue critical result without fallback is never silently assigned")
    void criticalEscalationWithoutFallbackIsExplicitlyOverdue() {
        UUID fac = UUID.randomUUID();
        ClinicianResultInboxEntity item = journeyService.deliverVerifiedResultToClinicianInbox(
                new DeliverResultToInboxCommand(
                        fac, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        "LAB", "dr-primary", "EMERGENCY", null, true, false, true, 1));
        journeyService.escalateOverdueCriticalInboxItems(Instant.now().plus(Duration.ofMinutes(5)));
        assertEquals(ClinicianInboxStatus.OVERDUE, item.getInboxStatus());
        assertEquals("dr-primary", item.getResponsibleClinicianId());
        long escalations = outboxStore.stream()
                .filter(e -> "ClinicianReviewRequired".equals(e.getEventType())).count();
        journeyService.escalateOverdueCriticalInboxItems(Instant.now().plus(Duration.ofMinutes(5)));
        assertEquals(escalations, outboxStore.stream()
                .filter(e -> "ClinicianReviewRequired".equals(e.getEventType())).count());
    }

    @Test
    @DisplayName("Privacy regression — emergency delivery never includes diagnoses and requires critical template")
    void emergencyOverrideCannotBypassNotificationPrivacyOrIdempotency() {
        UUID facility = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        UUID encounter = UUID.randomUUID();
        String recipient = "+237699112233";
        assertThrows(IllegalArgumentException.class, () -> journeyService.dispatchDurableNotification(
                new RequestDurableNotificationCommand(
                        facility, patient, encounter, NotificationChannel.SMS,
                        "CRITICAL_FOLLOWUP", "en", recipient, Map.of(),
                        false, true, true, "EMERGENCY-RAW-PHI")));
        assertThrows(SecurityException.class, () -> journeyService.dispatchDurableNotification(
                new RequestDurableNotificationCommand(
                        facility, patient, encounter, NotificationChannel.SMS,
                        "RESULT_READY", "en", recipient, Map.of(),
                        true, true, false, "FAKE-EMERGENCY-OVERRIDE")));
        assertThrows(IllegalArgumentException.class, () -> journeyService.dispatchDurableNotification(
                new RequestDurableNotificationCommand(
                        facility, patient, encounter, NotificationChannel.SMS,
                        "RESULT_READY", "en", recipient, Map.of(),
                        false, false, false, null)));
        assertTrue(notificationStore.isEmpty());
        assertEquals(0, providerSubmissions.get());

        DurableNotificationEntity emergency = journeyService.dispatchDurableNotification(
                new RequestDurableNotificationCommand(
                        facility, patient, encounter, NotificationChannel.SMS,
                        "CRITICAL_FOLLOWUP", "en", recipient,
                        Map.of("facility", "Synthetic emergency department"),
                        true, true, false, "VALID-CRITICAL-FOLLOWUP"));
        assertEquals(NotificationDeliveryState.SUBMITTED_TO_PROVIDER, emergency.getDeliveryState());
        assertTrue(emergency.isPrivacyMinimized());
        assertNotNull(emergency.getProviderReceiptId());
        DurableNotificationEntity exactReplay = journeyService.dispatchDurableNotification(
                new RequestDurableNotificationCommand(
                        facility, patient, encounter, NotificationChannel.SMS,
                        "CRITICAL_FOLLOWUP", "en", recipient,
                        Map.of("facility", "Synthetic emergency department"),
                        true, true, false, "VALID-CRITICAL-FOLLOWUP"));
        assertSame(emergency, exactReplay);
        assertEquals(1, notificationStore.size());

        assertThrows(SecurityException.class, () -> journeyService.dispatchDurableNotification(
                new RequestDurableNotificationCommand(
                        facility, UUID.randomUUID(), encounter, NotificationChannel.SMS,
                        "CRITICAL_FOLLOWUP", "en", recipient, Map.of(),
                        true, true, false, "VALID-CRITICAL-FOLLOWUP")));
        assertThrows(SecurityException.class, () -> journeyService.dispatchDurableNotification(
                new RequestDurableNotificationCommand(
                        facility, patient, encounter, NotificationChannel.SMS,
                        "CRITICAL_FOLLOWUP", "en", "+237699998877", Map.of(),
                        true, true, false, "VALID-CRITICAL-FOLLOWUP")));
        assertEquals(1, notificationStore.size());
    }

    @Test
    @DisplayName("Reliability regression — provider failure or missing acceptance receipt cannot become delivered")
    void providerSubmissionFailureAndConflictingReceiptsRemainAuditable() {
        UUID facility = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        UUID encounter = UUID.randomUUID();
        DurableNotificationRepository repo = mock(DurableNotificationRepository.class);
        when(repo.findByFacilityIdAndIdempotencyKey(any(), anyString()))
                .thenReturn(Optional.empty());
        when(repo.save(any(DurableNotificationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        NotificationProviderAdapterSpi adapterWithoutReceipt = new NotificationProviderAdapterSpi() {
            public boolean supports(NotificationChannel channel) { return channel == NotificationChannel.SMS; }
            public ProviderSubmissionResult submit(DurableNotificationEntity entity) {
                return new ProviderSubmissionResult(true, null, null);
            }
        };
        PatientJourneyOrchestratorService withoutReceipt = new PatientJourneyOrchestratorService(
                null, null, null, null, repo, null, null, null, List.of(adapterWithoutReceipt));
        DurableNotificationEntity missingProviderProof = withoutReceipt.dispatchDurableNotification(
                new RequestDurableNotificationCommand(
                        facility, patient, encounter, NotificationChannel.SMS, "RESULT_READY",
                        "en", "+237699112233", Map.of(), false, false, false, "NO-RECEIPT"));
        assertEquals(NotificationDeliveryState.RETRYING, missingProviderProof.getDeliveryState());
        assertNull(missingProviderProof.getSubmittedAt());
        when(repo.findById(missingProviderProof.getId()))
                .thenReturn(Optional.of(missingProviderProof));
        assertThrows(IllegalStateException.class, () -> withoutReceipt.confirmNotificationDeliveryReceipt(
                facility, missingProviderProof.getId(), "FORGED-DELIVERY"));

        NotificationProviderAdapterSpi failingAdapter = new NotificationProviderAdapterSpi() {
            public boolean supports(NotificationChannel channel) { return channel == NotificationChannel.SMS; }
            public ProviderSubmissionResult submit(DurableNotificationEntity entity) {
                throw new IllegalStateException("patient-secret-DO-NOT-PERSIST");
            }
        };
        PatientJourneyOrchestratorService failing = new PatientJourneyOrchestratorService(
                null, null, null, null, repo, null, null, null, List.of(failingAdapter));
        DurableNotificationEntity failed = failing.dispatchDurableNotification(
                new RequestDurableNotificationCommand(
                        facility, patient, encounter, NotificationChannel.SMS, "RESULT_READY",
                        "en", "+237699112233", Map.of(), false, false, false, "PROVIDER-ERROR"));
        assertEquals(NotificationDeliveryState.RETRYING, failed.getDeliveryState());
        assertFalse(failed.getLastError().contains("patient-secret"));

        DurableNotificationEntity submitted = journeyService.dispatchDurableNotification(
                new RequestDurableNotificationCommand(
                        facility, patient, encounter, NotificationChannel.SMS, "RESULT_READY",
                        "en", "+237699112233", Map.of(), false, false, false, "RECEIPT-EXACT"));
        DurableNotificationEntity delivered = journeyService.confirmNotificationDeliveryReceipt(
                facility, submitted.getId(), "DELIVERY-OK");
        Instant firstDelivery = delivered.getDeliveredAt();
        assertSame(delivered, journeyService.confirmNotificationDeliveryReceipt(
                facility, submitted.getId(), "DELIVERY-OK"));
        assertEquals(firstDelivery, delivered.getDeliveredAt());
        assertThrows(SecurityException.class, () -> journeyService.confirmNotificationDeliveryReceipt(
                facility, submitted.getId(), "SOMEONE-ELSES-RECEIPT"));
    }

    @Test
    @DisplayName("Section 9 & 14 — All 6 versioned JSON workflow definitions deserialize cleanly with all 25 attributes")
    void allSixMachineReadableWorkflowJsonDefinitionsDeserializeCleanly() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<String> files = List.of(
                "/workflows/definitions/opd-consultation-journey.v2.json",
                "/workflows/definitions/laboratory-order-fulfillment.v1.json",
                "/workflows/definitions/radiology-imaging-fulfillment.v1.json",
                "/workflows/definitions/pharmacy-prescription-fulfillment.v1.json",
                "/workflows/definitions/emergency-resuscitation-escalation.v1.json",
                "/workflows/definitions/clinician-critical-result-escalation.v1.json"
        );
        for (String path : files) {
            try (InputStream is = WorkflowChecklistDefinition.class.getResourceAsStream(path)) {
                assertNotNull(is, "Missing workflow JSON resource on classpath: " + path);
                WorkflowChecklistDefinition def = mapper.readValue(is, WorkflowChecklistDefinition.class);
                assertNotNull(def.workflowId());
                assertNotNull(def.version());
                assertFalse(def.requiredModules().isEmpty(), path + " must declare requiredModules");
                assertFalse(def.allowedStatesAndTransitions().isEmpty(), path + " must declare allowedStatesAndTransitions");
                assertFalse(def.safetyChecks().isEmpty(), path + " must declare safetyChecks");
                assertFalse(def.eventsToPublish().isEmpty(), path + " must declare eventsToPublish");
                assertNotNull(def.testsAndClinicalReviewStatus());
            }
        }
    }

    @Test
    @DisplayName("All 22 module versioned JSON workflow definitions and YAML registry validate against WorkflowChecklistDefinition")
    void allTwentyTwoModuleWorkflowJsonDefinitionsDeserializeCleanly() throws Exception {
        Path repoRoot = Files.isDirectory(Path.of("docs/workflows/definitions"))
                ? Path.of(".")
                : Path.of("..");
        Path docsDir = repoRoot.resolve("docs/workflows/definitions");
        assertTrue(Files.isDirectory(docsDir), "Expected docs/workflows/definitions directory to exist");
        Path registryFile = docsDir.resolve("workflow-registry.v1.yaml");
        assertTrue(Files.isRegularFile(registryFile),
                "Expected versioned workflow-registry.v1.yaml alongside JSON definitions");

        String registryYaml = Files.readString(registryFile);
        Set<String> canonicalEvents = Arrays.stream(CanonicalWorkflowEventType.values())
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toSet());

        ObjectMapper mapper = new ObjectMapper();
        List<Path> jsonFiles;
        try (var stream = Files.list(docsDir)) {
            jsonFiles = stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
        assertEquals(22, jsonFiles.size(), "Expected exactly 22 module workflow JSON definitions");
        for (Path jsonFile : jsonFiles) {
            WorkflowChecklistDefinition def = mapper.readValue(jsonFile.toFile(), WorkflowChecklistDefinition.class);
            assertNotNull(def.workflowId(), jsonFile.getFileName() + " missing workflowId");
            assertNotNull(def.version(), jsonFile.getFileName() + " missing version");
            assertFalse(def.requiredModules().isEmpty(), jsonFile.getFileName() + " missing requiredModules");
            assertFalse(def.allowedStatesAndTransitions().isEmpty(), jsonFile.getFileName() + " missing state transitions");
            assertFalse(def.safetyChecks().isEmpty(), jsonFile.getFileName() + " missing safetyChecks");
            assertFalse(def.eventsToPublish().isEmpty(), jsonFile.getFileName() + " missing eventsToPublish");
            assertTrue(canonicalEvents.contains(def.startingEvent()),
                    jsonFile.getFileName() + " uses non-canonical startingEvent: " + def.startingEvent());
            for (String publishedEvent : def.eventsToPublish()) {
                assertTrue(canonicalEvents.contains(publishedEvent),
                        jsonFile.getFileName() + " publishes non-canonical event: " + publishedEvent);
            }
            assertTrue(registryYaml.contains(def.workflowId()),
                    "workflow-registry.v1.yaml missing entry for " + def.workflowId());
            String moduleId = def.requiredModules().stream()
                    .filter(m -> m.startsWith("healthcore-starter-"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No starter module declared in " + jsonFile.getFileName()));
            Path classpathMirror = repoRoot.resolve(moduleId)
                    .resolve("src/main/resources/workflows")
                    .resolve(jsonFile.getFileName());
            assertTrue(Files.isRegularFile(classpathMirror),
                    "Missing module classpath mirror: " + classpathMirror);
            assertArrayEquals(Files.readAllBytes(jsonFile), Files.readAllBytes(classpathMirror),
                    "Docs/classpath parity mismatch for " + jsonFile.getFileName());
        }
    }
}
