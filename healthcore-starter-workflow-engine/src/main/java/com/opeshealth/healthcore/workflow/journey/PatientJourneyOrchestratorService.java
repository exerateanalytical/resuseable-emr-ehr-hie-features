/*
 * =============================================================================
 * Opes HealthCore SDK — Composable Patient Journey, Order Routing, Inbox & Notification Service
 * =============================================================================
 */
package com.opeshealth.healthcore.workflow.journey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.workflow.catalog.ReusableWorkflowCatalog;
import com.opeshealth.healthcore.workflow.domain.WorkflowEntities.WorkflowInstanceEntity;
import com.opeshealth.healthcore.workflow.journey.ClinicalJourneyContracts.*;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyEntities.*;
import com.opeshealth.healthcore.workflow.service.WorkflowOrchestrationService;
import com.opeshealth.healthcore.workflow.spi.WorkflowSpi.WorkflowPriority;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Composable parent-child clinical journey orchestrator enhancing {@code OPD_CONSULTATION_FLOW}
 * without forcing parallel departmental work into a single linear state machine.
 */
public class PatientJourneyOrchestratorService {

    private static final Set<String> FORBIDDEN_PLACEHOLDER_RECIPIENTS = Set.of(
            "+237670000000", "+0000000000", "0000000000", "placeholder@example.com", "test@test.com"
    );

    public interface WorkflowSubflowLinkRepository extends JpaRepository<WorkflowSubflowLinkEntity, UUID> {
        List<WorkflowSubflowLinkEntity> findByFacilityIdAndParentWorkflowId(UUID facilityId, UUID parentWorkflowId);
    }

    public interface DepartmentOrderRoutingRepository extends JpaRepository<DepartmentOrderRoutingEntity, UUID> {
        Optional<DepartmentOrderRoutingEntity> findByFacilityIdAndIdempotencyKey(UUID facilityId, String idempotencyKey);
        List<DepartmentOrderRoutingEntity> findByFacilityIdAndEncounterId(UUID facilityId, UUID encounterId);
        Optional<DepartmentOrderRoutingEntity> findByFacilityIdAndOrderId(UUID facilityId, UUID orderId);
    }

    public interface ClinicianResultInboxRepository extends JpaRepository<ClinicianResultInboxEntity, UUID> {
        Optional<ClinicianResultInboxEntity> findByFacilityIdAndResultIdAndResponsibleClinicianId(
                UUID facilityId, UUID resultId, String responsibleClinicianId);
        List<ClinicianResultInboxEntity> findByFacilityIdAndResponsibleClinicianIdOrderByCriticalFlagDescReceivedAtAsc(
                UUID facilityId, String responsibleClinicianId);
        List<ClinicianResultInboxEntity> findByFacilityIdAndEncounterIdOrderByReceivedAtAsc(UUID facilityId, UUID encounterId);
        List<ClinicianResultInboxEntity> findByCriticalFlagTrueAndAcknowledgedAtIsNullAndEscalationDeadlineAtBefore(Instant cutoff);
    }

    public interface DurableNotificationRepository extends JpaRepository<DurableNotificationEntity, UUID> {
        Optional<DurableNotificationEntity> findByFacilityIdAndIdempotencyKey(UUID facilityId, String idempotencyKey);
        List<DurableNotificationEntity> findByFacilityIdAndPatientIdOrderByCreatedAtDesc(UUID facilityId, UUID patientId);
    }

    public interface DurableProcessedEventRepository extends JpaRepository<DurableProcessedEventEntity, UUID> {
        Optional<DurableProcessedEventEntity> findByFacilityIdAndIdempotencyKey(UUID facilityId, String idempotencyKey);
    }

    /** Section 8 — Pluggable delivery provider adapter SPI (SMS, WhatsApp, Email, Push, In-App). */
    public interface NotificationProviderAdapterSpi {
        boolean supports(NotificationChannel channel);
        ProviderSubmissionResult submit(DurableNotificationEntity notification);
    }

    public record ProviderSubmissionResult(boolean accepted, String providerReceiptId, String errorDetail) {}

    /**
     * Optional integration port: keeps the generic workflow SDK independent of the
     * queue starter. A consumer supplies a durable, idempotent adapter when it
     * wants automatic departmental worklists.
     */
    public interface OrderRoutingQueueProvisioningSpi {
        UUID createOrGetQueueEntry(DepartmentOrderRoutingEntity routing, boolean emergency, String actorId);
        void synchronizeFinancialClearance(DepartmentOrderRoutingEntity routing);
        void requireQueueCompleted(DepartmentOrderRoutingEntity routing);
    }

    public record RouteSignedOrderCommand(
            UUID facilityId,
            UUID patientId,
            UUID encounterId,
            UUID parentWorkflowId,
            UUID orderId,
            OrderTargetDepartment targetDepartment,
            String externalPharmacyCode,
            boolean emergencyUrgent,
            boolean insuranceAuthorized,
            boolean creditApproved,
            boolean sponsored,
            boolean waived,
            long billedAmountMinor,
            long paidAmountMinor,
            String actorId,
            String idempotencyKey
    ) {}

    public record DeliverResultToInboxCommand(
            UUID facilityId,
            UUID patientId,
            UUID encounterId,
            UUID orderId,
            UUID resultId,
            String resultDomain,
            String responsibleClinicianId,
            String careTeamCode,
            String fallbackClinicianId,
            boolean criticalResult,
            boolean amendedResult,
            boolean returnToQueueRequired,
            Integer criticalEscalationMinutes
    ) {}

    public record RequestDurableNotificationCommand(
            UUID facilityId,
            UUID patientId,
            UUID encounterId,
            NotificationChannel channel,
            String templateCode,
            String languageCode,
            String recipientAddress,
            Map<String, String> templateVars,
            boolean patientOptedOut,
            boolean emergencyOverride,
            boolean containsRawClinicalDiagnosis,
            String idempotencyKey
    ) {}

    public record EncounterFinalizationCheck(
            boolean canFinalize,
            List<String> blockingReasons
    ) {}

    private final WorkflowOrchestrationService workflowService;
    private final WorkflowSubflowLinkRepository subflowRepo;
    private final DepartmentOrderRoutingRepository routingRepo;
    private final ClinicianResultInboxRepository inboxRepo;
    private final DurableNotificationRepository notificationRepo;
    private final DurableProcessedEventRepository processedEventRepo;
    private final OutboxRelayService.OutboxRepository outboxRepo;
    private final WorkflowBillingClearancePolicySpi billingPolicy;
    private final List<NotificationProviderAdapterSpi> notificationAdapters;
    private final OrderRoutingQueueProvisioningSpi queueProvisioningSpi;
    // Spring Boot 4 may use Jackson 3 for its web stack while this SDK still
    // uses the Jackson 2 ObjectMapper type. Register ISO-8601 Instant handling
    // explicitly to avoid silently failing every clinical outbox transaction.
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(
            new com.fasterxml.jackson.databind.module.SimpleModule("HealthCoreInstant")
                    .addSerializer(Instant.class, new com.fasterxml.jackson.databind.JsonSerializer<Instant>() {
                        @Override
                        public void serialize(
                                Instant value,
                                com.fasterxml.jackson.core.JsonGenerator json,
                                com.fasterxml.jackson.databind.SerializerProvider provider)
                                throws java.io.IOException {
                            json.writeString(value.toString());
                        }
                    }));

    public PatientJourneyOrchestratorService(
            WorkflowOrchestrationService workflowService,
            WorkflowSubflowLinkRepository subflowRepo,
            DepartmentOrderRoutingRepository routingRepo,
            ClinicianResultInboxRepository inboxRepo,
            DurableNotificationRepository notificationRepo,
            DurableProcessedEventRepository processedEventRepo,
            OutboxRelayService.OutboxRepository outboxRepo,
            WorkflowBillingClearancePolicySpi billingPolicy,
            List<NotificationProviderAdapterSpi> notificationAdapters) {
        this(workflowService, subflowRepo, routingRepo, inboxRepo, notificationRepo,
                processedEventRepo, outboxRepo, billingPolicy, notificationAdapters, null);
    }

    /** Constructor used by auto-configuration when a departmental queue bridge exists. */
    public PatientJourneyOrchestratorService(
            WorkflowOrchestrationService workflowService,
            WorkflowSubflowLinkRepository subflowRepo,
            DepartmentOrderRoutingRepository routingRepo,
            ClinicianResultInboxRepository inboxRepo,
            DurableNotificationRepository notificationRepo,
            DurableProcessedEventRepository processedEventRepo,
            OutboxRelayService.OutboxRepository outboxRepo,
            WorkflowBillingClearancePolicySpi billingPolicy,
            List<NotificationProviderAdapterSpi> notificationAdapters,
            OrderRoutingQueueProvisioningSpi queueProvisioningSpi) {
        this.workflowService = workflowService;
        this.subflowRepo = subflowRepo;
        this.routingRepo = routingRepo;
        this.inboxRepo = inboxRepo;
        this.notificationRepo = notificationRepo;
        this.processedEventRepo = processedEventRepo;
        this.outboxRepo = outboxRepo;
        this.billingPolicy = billingPolicy != null ? billingPolicy : new DefaultFacilityBillingClearancePolicy();
        this.notificationAdapters = notificationAdapters != null ? List.copyOf(notificationAdapters) : List.of();
        this.queueProvisioningSpi = queueProvisioningSpi;
    }

    /**
     * Section 2 &amp; 3 — Starts a parent {@code OPD_CONSULTATION_FLOW} bound to an authoritative
     * MPI {@code patientId} and a distinct visit {@code encounterId}, preserving identity separation.
     */
    @Transactional
    @ClinicalAudit(action = "JOURNEY_START_OPD", resourceType = "WorkflowInstance")
    public WorkflowInstanceEntity startParentOutpatientJourney(
            UUID facilityId, UUID patientId, UUID encounterId, boolean returningPatient,
            boolean emergencyArrival, String actorId) {
        Objects.requireNonNull(facilityId, "facilityId is required");
        Objects.requireNonNull(patientId, "Authoritative MPI patientId is required");
        Objects.requireNonNull(encounterId, "Visit encounterId is required");

        WorkflowPriority priority = emergencyArrival ? WorkflowPriority.STAT : WorkflowPriority.ROUTINE;
        WorkflowInstanceEntity parent = workflowService.startWorkflow(
                facilityId,
                ReusableWorkflowCatalog.OPD_CONSULTATION_FLOW,
                patientId,
                encounterId.toString(),
                priority,
                Map.of(
                        "encounterId", encounterId.toString(),
                        "returningPatient", returningPatient,
                        "emergencyArrival", emergencyArrival
                ),
                actorId,
                "RECEPTION_OR_TRIAGE");

        emitCanonicalEvent(CanonicalDomainEventEnvelope.create(
                returningPatient ? CanonicalWorkflowEventType.PatientMatched : CanonicalWorkflowEventType.PatientRegistered,
                facilityId, patientId, encounterId, null, parent.getId(), null,
                "workflow-engine", actorId, "IDENT-" + encounterId, Map.of("returningPatient", returningPatient)));
        emitCanonicalEvent(CanonicalDomainEventEnvelope.create(
                CanonicalWorkflowEventType.EncounterStarted,
                facilityId, patientId, encounterId, null, parent.getId(), null,
                "workflow-engine", actorId, "ENC-START-" + encounterId, Map.of("workflowInstanceId", parent.getId().toString())));
        return parent;
    }

    /**
     * Section 3 &amp; 5 — Evaluates consultation clearance after triage; clinically unstable patients
     * (ESI 1–2 / emergencyFlag) receive immediate {@code EMERGENCY_OVERRIDE} bypassing routine prepayment.
     */
    @Transactional
    @ClinicalAudit(action = "JOURNEY_TRIAGE_CLEARANCE", resourceType = "WorkflowInstance")
    public BillingClearanceDecision evaluateTriageAndConsultationClearance(
            UUID facilityId, UUID patientId, UUID encounterId, UUID parentWorkflowId,
            int esiLevel, boolean unstableEmergency,
            BillingClearanceEvaluationRequest clearanceRequest,
            FacilityBillingPolicyConfig facilityConfig,
            String actorId) {
        boolean isEmergency = unstableEmergency || esiLevel <= 2 || clearanceRequest.emergencyUrgent();
        BillingClearanceEvaluationRequest effectiveReq = new BillingClearanceEvaluationRequest(
                facilityId, patientId, encounterId, null,
                isEmergency ? ServiceClearanceCategory.EMERGENCY_STABILIZATION : ServiceClearanceCategory.CONSULTATION,
                isEmergency, false,
                clearanceRequest.insuranceAuthorized(), clearanceRequest.creditApproved(),
                clearanceRequest.sponsored(), clearanceRequest.waived(),
                clearanceRequest.billedAmountMinor(), clearanceRequest.paidAmountMinor());

        BillingClearanceDecision decision = billingPolicy.evaluate(effectiveReq, facilityConfig);
        emitCanonicalEvent(CanonicalDomainEventEnvelope.create(
                CanonicalWorkflowEventType.TriageCompleted,
                facilityId, patientId, encounterId, null, parentWorkflowId, null,
                "workflow-engine", actorId, "TRIAGE-" + encounterId + "-" + esiLevel,
                Map.of("esiLevel", esiLevel, "clearance", decision.outcome().name(), "eligible", decision.fulfillmentEligible())));
        return decision;
    }

    /**
     * Section 6 &amp; 10 — Replay-safe automatic routing of signed laboratory, imaging, in-house pharmacy,
     * or external pharmacy orders into independent parallel subworkflows and departmental tasks.
     */
    @Transactional
    @ClinicalAudit(action = "JOURNEY_ROUTE_SIGNED_ORDER", resourceType = "DepartmentOrderRouting")
    public DepartmentOrderRoutingEntity routeSignedOrder(
            RouteSignedOrderCommand cmd, FacilityBillingPolicyConfig facilityConfig) {
        Objects.requireNonNull(cmd, "RouteSignedOrderCommand is required");
        if (cmd.facilityId() == null || cmd.patientId() == null || cmd.encounterId() == null
                || cmd.orderId() == null || cmd.targetDepartment() == null
                || cmd.actorId() == null || cmd.actorId().isBlank()) {
            throw new IllegalArgumentException(
                    "Facility, patient, encounter, signed order, target department and actor are required");
        }
        if (cmd.billedAmountMinor() < 0 || cmd.paidAmountMinor() < 0) {
            throw new IllegalArgumentException("Order amounts cannot be negative");
        }
        String idempKey = (cmd.idempotencyKey() != null && !cmd.idempotencyKey().isBlank())
                ? cmd.idempotencyKey()
                : "ROUTE-" + cmd.orderId() + "-" + cmd.targetDepartment().name();

        Optional<DepartmentOrderRoutingEntity> existing =
                routingRepo.findByFacilityIdAndIdempotencyKey(cmd.facilityId(), idempKey);
        if (existing.isPresent()) {
            verifySameRouteIdentity(existing.get(), cmd);
            return ensureDepartmentQueue(existing.get(), cmd.emergencyUrgent(), cmd.actorId());
        }
        // The domain maps one clinical order to a single department routing.
        // Replaying the order with a new idempotency key must not create a second task.
        Optional<DepartmentOrderRoutingEntity> previousOrder =
                routingRepo.findByFacilityIdAndOrderId(cmd.facilityId(), cmd.orderId());
        if (previousOrder.isPresent()) {
            verifySameRouteIdentity(previousOrder.get(), cmd);
            return ensureDepartmentQueue(previousOrder.get(), cmd.emergencyUrgent(), cmd.actorId());
        }

        ServiceClearanceCategory cat = switch (cmd.targetDepartment()) {
            case LABORATORY -> ServiceClearanceCategory.LABORATORY;
            case RADIOLOGY -> ServiceClearanceCategory.IMAGING;
            case PHARMACY, EXTERNAL_PHARMACY -> ServiceClearanceCategory.PHARMACY;
            case PROCEDURE, REFERRAL -> ServiceClearanceCategory.PROCEDURE;
        };

        BillingClearanceDecision clearance = billingPolicy.evaluate(
                new BillingClearanceEvaluationRequest(
                        cmd.facilityId(), cmd.patientId(), cmd.encounterId(), cmd.orderId(),
                        cat, cmd.emergencyUrgent(), false,
                        cmd.insuranceAuthorized(), cmd.creditApproved(), cmd.sponsored(), cmd.waived(),
                        cmd.billedAmountMinor(), cmd.paidAmountMinor()),
                facilityConfig);

        UUID childWorkflowId = null;
        if (workflowService != null) {
            String childCode = switch (cmd.targetDepartment()) {
                case LABORATORY -> ReusableWorkflowCatalog.DIAGNOSTIC_LAB_FLOW;
                case RADIOLOGY -> ReusableWorkflowCatalog.RADIOLOGY_IMAGING_FULFILLMENT;
                case PHARMACY, EXTERNAL_PHARMACY -> ReusableWorkflowCatalog.PHARMACY_DISPENSE_FLOW;
                case REFERRAL -> ReusableWorkflowCatalog.CLOSED_LOOP_REFERRAL_FLOW;
                default -> null;
            };
            if (childCode != null) {
                WorkflowInstanceEntity child = workflowService.startWorkflow(
                        cmd.facilityId(), childCode, cmd.patientId(), cmd.orderId().toString(),
                        cmd.emergencyUrgent() ? WorkflowPriority.STAT : WorkflowPriority.ROUTINE,
                        Map.of("orderId", cmd.orderId().toString(),
                                "targetDepartment", cmd.targetDepartment().name(),
                                "externalPharmacyCode", cmd.externalPharmacyCode() != null ? cmd.externalPharmacyCode() : ""),
                        cmd.actorId(), "ORDER_ROUTER");
                childWorkflowId = child.getId();
                if (cmd.parentWorkflowId() != null) {
                    WorkflowSubflowLinkEntity link = new WorkflowSubflowLinkEntity();
                    link.setFacilityId(cmd.facilityId());
                    link.setParentWorkflowId(cmd.parentWorkflowId());
                    link.setChildWorkflowId(childWorkflowId);
                    link.setSubflowType(cmd.targetDepartment().name());
                    link.setEncounterId(cmd.encounterId());
                    link.setOrderId(cmd.orderId());
                    subflowRepo.save(link);
                }
            }
        }

        DepartmentOrderRoutingEntity routing = new DepartmentOrderRoutingEntity();
        routing.setFacilityId(cmd.facilityId());
        routing.setPatientId(cmd.patientId());
        routing.setEncounterId(cmd.encounterId());
        routing.setOrderId(cmd.orderId());
        routing.setParentWorkflowId(cmd.parentWorkflowId());
        routing.setChildWorkflowId(childWorkflowId);
        routing.setTargetDepartment(cmd.targetDepartment());
        routing.setExternalPharmacyCode(cmd.externalPharmacyCode());
        routing.setFinancialClearance(clearance.outcome());
        routing.setRoutingStatus(clearance.fulfillmentEligible()
                ? OrderRoutingStatus.ACTIVE_IN_DEPARTMENT
                : OrderRoutingStatus.ROUTED_AWAITING_CLEARANCE);
        routing.setIdempotencyKey(idempKey);

        DepartmentOrderRoutingEntity saved = routingRepo.save(routing);
        saved = ensureDepartmentQueue(saved, cmd.emergencyUrgent(), cmd.actorId());

        CanonicalWorkflowEventType deptEvent = switch (cmd.targetDepartment()) {
            case LABORATORY -> CanonicalWorkflowEventType.LaboratoryOrderAccepted;
            case RADIOLOGY -> CanonicalWorkflowEventType.ImagingOrderAccepted;
            case PHARMACY, EXTERNAL_PHARMACY -> CanonicalWorkflowEventType.PrescriptionRouted;
            default -> CanonicalWorkflowEventType.ClinicalOrderSigned;
        };
        emitCanonicalEvent(CanonicalDomainEventEnvelope.create(
                deptEvent, cmd.facilityId(), cmd.patientId(), cmd.encounterId(), cmd.orderId(),
                cmd.parentWorkflowId(), null, "workflow-engine", cmd.actorId(),
                "EVT-" + idempKey, Map.of("clearance", clearance.outcome().name(), "eligible", clearance.fulfillmentEligible())));
        return saved;
    }

    /**
     * Creates the operational department queue entry in the SAME transaction as the
     * routing record, or links a previously committed idempotent queue task.
     *
     * External pharmacy handoffs are deliberately not inserted into an in-house
     * dispensing queue. They must have their own authenticated external adapter.
     */
    private DepartmentOrderRoutingEntity ensureDepartmentQueue(
            DepartmentOrderRoutingEntity routing, boolean emergency, String actorId) {
        if (queueProvisioningSpi == null || routing.getQueueEntryId() != null
                || routing.getTargetDepartment() == OrderTargetDepartment.EXTERNAL_PHARMACY) {
            return routing;
        }
        UUID queueId = queueProvisioningSpi.createOrGetQueueEntry(routing, emergency, actorId);
        if (queueId == null) {
            throw new IllegalStateException("Internal departmental routing was not provisioned into a queue");
        }
        routing.setQueueEntryId(queueId);
        return routingRepo.save(routing);
    }

    private static void verifySameRouteIdentity(
            DepartmentOrderRoutingEntity previous, RouteSignedOrderCommand command) {
        if (!Objects.equals(previous.getFacilityId(), command.facilityId())
                || !Objects.equals(previous.getPatientId(), command.patientId())
                || !Objects.equals(previous.getEncounterId(), command.encounterId())
                || !Objects.equals(previous.getOrderId(), command.orderId())
                || previous.getTargetDepartment() != command.targetDepartment()
                || !Objects.equals(previous.getExternalPharmacyCode(), command.externalPharmacyCode())) {
            throw new SecurityException(
                    "Routing replay conflicts with the original patient, encounter, order or department");
        }
    }

    /**
     * Section 5 &amp; 10 — Idempotent payment confirmation releasing a financially held clinical order
     * without duplicating orders, routing tasks, or financial postings on event replay.
     */
    @Transactional
    @ClinicalAudit(action = "JOURNEY_CONFIRM_PAYMENT_CLEARANCE", resourceType = "DepartmentOrderRouting")
    public DepartmentOrderRoutingEntity confirmPaymentAndReleaseOrder(
            UUID facilityId, UUID orderId, PaymentClearanceOutcome confirmedOutcome,
            String paymentIdempotencyKey, String actorId) {
        if (facilityId == null || orderId == null || confirmedOutcome == null
                || !confirmedOutcome.permitsFulfillmentActivation()
                || paymentIdempotencyKey == null || paymentIdempotencyKey.isBlank()
                || actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException(
                    "Verified eligible clearance, signed order, actor and stable payment event key required");
        }
        DepartmentOrderRoutingEntity routing = routingRepo.findByFacilityIdAndOrderId(facilityId, orderId)
                .orElseThrow(() -> new NoSuchElementException("Order routing task not found for order " + orderId));
        if (!facilityId.equals(routing.getFacilityId())) {
            throw new SecurityException("Cross-facility payment release denied");
        }
        if (routing.getRoutingStatus() == OrderRoutingStatus.COMPLETED
                || routing.getRoutingStatus() == OrderRoutingStatus.CANCELLED) {
            throw new IllegalStateException("Completed or cancelled work cannot receive another payment release");
        }
        if (processedEventRepo.findByFacilityIdAndIdempotencyKey(facilityId, paymentIdempotencyKey).isPresent()) {
            if (routing.getFinancialClearance() != confirmedOutcome
                    || routing.getRoutingStatus() == OrderRoutingStatus.ROUTED_AWAITING_CLEARANCE) {
                throw new SecurityException("Payment replay key does not match the original cleared order state");
            }
            return routing; // Idempotent replay: no duplicate state transition or event emission.
        }

        DurableProcessedEventEntity proc = new DurableProcessedEventEntity();
        proc.setFacilityId(facilityId);
        proc.setIdempotencyKey(paymentIdempotencyKey);
        proc.setEventType(CanonicalWorkflowEventType.ServiceCleared.name());
        processedEventRepo.save(proc);

        routing.setFinancialClearance(confirmedOutcome);
        if (confirmedOutcome.permitsFulfillmentActivation()
                && routing.getRoutingStatus() == OrderRoutingStatus.ROUTED_AWAITING_CLEARANCE) {
            routing.setRoutingStatus(OrderRoutingStatus.ACTIVE_IN_DEPARTMENT);
        }
        DepartmentOrderRoutingEntity saved = routingRepo.save(routing);
        if (queueProvisioningSpi != null
                && routing.getTargetDepartment() != OrderTargetDepartment.EXTERNAL_PHARMACY) {
            saved = ensureDepartmentQueue(saved,
                    confirmedOutcome == PaymentClearanceOutcome.EMERGENCY_OVERRIDE, actorId);
            queueProvisioningSpi.synchronizeFinancialClearance(saved);
        }
        emitCanonicalEvent(CanonicalDomainEventEnvelope.create(
                CanonicalWorkflowEventType.ServiceCleared, facilityId, routing.getPatientId(),
                routing.getEncounterId(), orderId, routing.getParentWorkflowId(), null,
                "workflow-engine", actorId, paymentIdempotencyKey, Map.of("outcome", confirmedOutcome.name())));
        return saved;
    }

    @Transactional
    public DepartmentOrderRoutingEntity markRoutedOrderCompleted(UUID facilityId, UUID orderId) {
        DepartmentOrderRoutingEntity routing = routingRepo.findByFacilityIdAndOrderId(facilityId, orderId)
                .orElseThrow(() -> new NoSuchElementException("Order routing task not found for order " + orderId));
        if (routing.getRoutingStatus() == OrderRoutingStatus.COMPLETED) {
            return routing; // Durable retry must preserve the first completion timestamp.
        }
        if (routing.getFinancialClearance() == null
                || !routing.getFinancialClearance().permitsFulfillmentActivation()
                || (routing.getRoutingStatus() != OrderRoutingStatus.ACTIVE_IN_DEPARTMENT
                    && routing.getRoutingStatus() != OrderRoutingStatus.IN_PROGRESS)) {
            throw new IllegalStateException(
                    "Order cannot be completed in state " + routing.getRoutingStatus()
                    + " with clearance " + routing.getFinancialClearance());
        }
        if (queueProvisioningSpi != null
                && routing.getTargetDepartment() != OrderTargetDepartment.EXTERNAL_PHARMACY) {
            if (routing.getQueueEntryId() == null) {
                throw new IllegalStateException("Order cannot be completed without a linked departmental queue entry");
            }
            queueProvisioningSpi.requireQueueCompleted(routing);
        }
        routing.setRoutingStatus(OrderRoutingStatus.COMPLETED);
        routing.setCompletedAt(Instant.now());
        return routingRepo.save(routing);
    }

    /**
     * Section 7 — Delivers a verified lab or imaging result reference to the responsible clinician's
     * durable inbox. Critical findings immediately enter {@code CRITICAL_ESCALATION_PENDING} regardless
     * of patient financial state, and electronic notification NEVER counts as acknowledgment.
     */
    @Transactional
    @ClinicalAudit(action = "CLINICIAN_INBOX_DELIVER_RESULT", resourceType = "ClinicianResultInbox")
    public ClinicianResultInboxEntity deliverVerifiedResultToClinicianInbox(DeliverResultToInboxCommand cmd) {
        Objects.requireNonNull(cmd, "DeliverResultToInboxCommand is required");
        if (cmd.facilityId() == null || cmd.patientId() == null || cmd.encounterId() == null
                || cmd.orderId() == null || cmd.resultId() == null
                || cmd.resultDomain() == null || cmd.resultDomain().isBlank()
                || cmd.responsibleClinicianId() == null || cmd.responsibleClinicianId().isBlank()) {
            throw new IllegalArgumentException("Verified result must identify facility, patient, encounter, order, result, domain and responsible clinician");
        }
        Optional<ClinicianResultInboxEntity> existing = inboxRepo
                .findByFacilityIdAndResultIdAndResponsibleClinicianId(cmd.facilityId(), cmd.resultId(), cmd.responsibleClinicianId());
        if (existing.isPresent()) {
            ClinicianResultInboxEntity original = existing.get();
            if (!Objects.equals(original.getFacilityId(), cmd.facilityId())
                    || !Objects.equals(original.getPatientId(), cmd.patientId())
                    || !Objects.equals(original.getEncounterId(), cmd.encounterId())
                    || !Objects.equals(original.getOrderId(), cmd.orderId())
                    || !Objects.equals(original.getResultDomain(), cmd.resultDomain())) {
                throw new SecurityException("Result replay attempts to change a patient's original order/encounter");
            }
            if (!cmd.amendedResult()) {
                return original;
            }
        }

        ClinicianResultInboxEntity item = existing.orElseGet(ClinicianResultInboxEntity::new);
        item.setFacilityId(cmd.facilityId());
        item.setPatientId(cmd.patientId());
        item.setEncounterId(cmd.encounterId());
        item.setOrderId(cmd.orderId());
        item.setResultId(cmd.resultId());
        item.setResultDomain(cmd.resultDomain());
        item.setResponsibleClinicianId(cmd.responsibleClinicianId());
        item.setCareTeamCode(cmd.careTeamCode());
        item.setFallbackClinicianId(cmd.fallbackClinicianId());
        item.setCriticalFlag(cmd.criticalResult());
        item.setAmendedResult(cmd.amendedResult());
        if (cmd.amendedResult()) {
            // Any amended result, including a *critical* amendment, invalidates the
            // previous clinician acknowledgment and must trigger a fresh review.
            item.setAcknowledgedAt(null);
            item.setAcknowledgedBy(null);
            item.setAcknowledgmentNotes(null);
        }
        // Creating an inbox task does not prove that an electronic notification was sent.
        item.setElectronicNotificationSent(false);
        item.setReturnToQueueRequired(cmd.returnToQueueRequired());
        item.setGroupedReadinessKey(cmd.encounterId() != null ? "ENC-" + cmd.encounterId() : "ORD-" + cmd.orderId());

        if (cmd.criticalResult()) {
            item.setInboxStatus(ClinicianInboxStatus.CRITICAL_ESCALATION_PENDING);
            int mins = cmd.criticalEscalationMinutes() != null && cmd.criticalEscalationMinutes() > 0
                    ? cmd.criticalEscalationMinutes() : 15;
            item.setEscalationDeadlineAt(Instant.now().plus(Duration.ofMinutes(mins)));
        } else if (cmd.amendedResult()) {
            item.setInboxStatus(ClinicianInboxStatus.ACTION_REQUIRED);
            item.setAcknowledgedAt(null);
            item.setAcknowledgedBy(null);
        } else {
            item.setInboxStatus(ClinicianInboxStatus.AWAITING_REVIEW);
        }

        ClinicianResultInboxEntity saved = inboxRepo.save(item);
        emitCanonicalEvent(CanonicalDomainEventEnvelope.create(
                cmd.criticalResult() ? CanonicalWorkflowEventType.CriticalResultDetected : CanonicalWorkflowEventType.ClinicianReviewRequired,
                cmd.facilityId(), cmd.patientId(), cmd.encounterId(), cmd.orderId(), null, null,
                "workflow-engine", cmd.responsibleClinicianId(),
                "INBOX-" + cmd.resultId() + (cmd.amendedResult() ? "-AMEND" : ""),
                Map.of("inboxId", saved.getId().toString(), "critical", cmd.criticalResult())));
        return saved;
    }

    /** Section 7 — Explicit clinician acknowledgment (distinct from electronic delivery). */
    @Transactional
    @ClinicalAudit(action = "CLINICIAN_INBOX_ACKNOWLEDGE", resourceType = "ClinicianResultInbox")
    public ClinicianResultInboxEntity acknowledgeClinicianInboxItem(
            UUID facilityId, UUID inboxItemId, String clinicianId, String notes, boolean markResolved) {
        ClinicianResultInboxEntity item = inboxRepo.findById(inboxItemId)
                .orElseThrow(() -> new NoSuchElementException("Clinician inbox item not found: " + inboxItemId));
        if (!facilityId.equals(item.getFacilityId())) {
            throw new SecurityException("Cross-facility clinician inbox access denied");
        }
        if (clinicianId == null || clinicianId.isBlank()) {
            throw new IllegalArgumentException("Acknowledging clinicianId is required");
        }
        if (!clinicianId.trim().equals(item.getResponsibleClinicianId())) {
            throw new SecurityException("Only the currently responsible clinician may acknowledge this result");
        }
        if (item.isCriticalFlag() && (notes == null || notes.isBlank())) {
            throw new IllegalArgumentException("Critical results require documented clinician review/read-back notes");
        }
        if (item.getAcknowledgedAt() != null) {
            if (clinicianId.trim().equals(item.getAcknowledgedBy())
                    && ((markResolved && item.getInboxStatus() == ClinicianInboxStatus.RESOLVED)
                    || (!markResolved && item.getInboxStatus() == ClinicianInboxStatus.ACKNOWLEDGED))) {
                return item; // Repeat receipt does not rewrite the original timestamp or emit duplicate events.
            }
            throw new IllegalStateException("Result is already acknowledged; use a governed follow-up transition");
        }
        item.setAcknowledgedBy(clinicianId.trim());
        item.setAcknowledgedAt(Instant.now());
        item.setAcknowledgmentNotes(notes);
        item.setInboxStatus(markResolved ? ClinicianInboxStatus.RESOLVED : ClinicianInboxStatus.ACKNOWLEDGED);
        ClinicianResultInboxEntity saved = inboxRepo.save(item);
        emitCanonicalEvent(CanonicalDomainEventEnvelope.create(
                CanonicalWorkflowEventType.ClinicianResultAcknowledged, facilityId,
                item.getPatientId(), item.getEncounterId(), item.getOrderId(), null, null,
                "workflow-engine", clinicianId, "ACK-" + inboxItemId, Map.of("status", saved.getInboxStatus().name())));
        return saved;
    }

    /** Section 7 — Escalates overdue unacknowledged critical results to fallback clinical responsibility. */
    @Transactional
    public List<ClinicianResultInboxEntity> escalateOverdueCriticalInboxItems(Instant now) {
        List<ClinicianResultInboxEntity> overdue = inboxRepo
                .findByCriticalFlagTrueAndAcknowledgedAtIsNullAndEscalationDeadlineAtBefore(now != null ? now : Instant.now());
        for (ClinicianResultInboxEntity item : overdue) {
            if (item.getInboxStatus() == ClinicianInboxStatus.ESCALATED
                    || item.getInboxStatus() == ClinicianInboxStatus.OVERDUE
                    || item.getInboxStatus() == ClinicianInboxStatus.RESOLVED) {
                continue; // Avoid repeat escalation on each SLA scan.
            }
            boolean hasFallback = item.getFallbackClinicianId() != null
                    && !item.getFallbackClinicianId().isBlank();
            item.setInboxStatus(hasFallback ? ClinicianInboxStatus.ESCALATED : ClinicianInboxStatus.OVERDUE);
            if (hasFallback) {
                item.setResponsibleClinicianId(item.getFallbackClinicianId());
            }
            inboxRepo.save(item);
            emitCanonicalEvent(CanonicalDomainEventEnvelope.create(
                    CanonicalWorkflowEventType.ClinicianReviewRequired,
                    item.getFacilityId(), item.getPatientId(), item.getEncounterId(), item.getOrderId(),
                    null, null, "workflow-engine", "SYSTEM", "ESCALATE-" + item.getId(),
                    Map.of("inboxId", item.getId().toString(),
                            "responsibleClinicianId", String.valueOf(item.getResponsibleClinicianId()))));
        }
        return overdue;
    }

    /**
     * Section 8 — Durable, consent-enforced, privacy-minimized patient notification dispatch.
     * Never uses placeholder recipient addresses and only marks {@code DELIVERED} upon real receipt confirmation.
     */
    @Transactional
    @ClinicalAudit(action = "NOTIFICATION_DISPATCH", resourceType = "DurableNotification")
    public DurableNotificationEntity dispatchDurableNotification(RequestDurableNotificationCommand cmd) {
        Objects.requireNonNull(cmd, "RequestDurableNotificationCommand is required");
        if (cmd.facilityId() == null || cmd.patientId() == null || cmd.encounterId() == null
                || cmd.templateCode() == null || cmd.templateCode().isBlank()
                || cmd.idempotencyKey() == null || cmd.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException(
                    "Facility, patient, encounter, template and stable idempotency key are required");
        }
        String idempKey = cmd.idempotencyKey().trim();

        Optional<DurableNotificationEntity> existing = notificationRepo
                .findByFacilityIdAndIdempotencyKey(cmd.facilityId(), idempKey);
        if (existing.isPresent()) {
            DurableNotificationEntity previous = existing.get();
            NotificationChannel requestedChannel =
                    cmd.channel() != null ? cmd.channel() : NotificationChannel.SMS;
            if (!Objects.equals(previous.getFacilityId(), cmd.facilityId())
                    || !Objects.equals(previous.getPatientId(), cmd.patientId())
                    || !Objects.equals(previous.getEncounterId(), cmd.encounterId())
                    || !Objects.equals(previous.getTemplateCode(), cmd.templateCode())
                    || previous.getChannel() != requestedChannel
                    || !Objects.equals(previous.getRecipientAddress(),
                            cmd.recipientAddress() == null ? null : cmd.recipientAddress().trim())
                    || previous.isEmergencyOverride() != cmd.emergencyOverride()) {
                throw new SecurityException(
                        "Notification idempotency key conflicts with the original patient, recipient or purpose");
            }
            return previous;
        }

        if (cmd.recipientAddress() == null || cmd.recipientAddress().isBlank()
                || FORBIDDEN_PLACEHOLDER_RECIPIENTS.contains(cmd.recipientAddress().trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Valid real recipient address is required; placeholder addresses are forbidden");
        }
        if (cmd.containsRawClinicalDiagnosis()) {
            throw new IllegalArgumentException(
                    "Patient-facing notification messages cannot disclose raw clinical diagnoses, including emergencies");
        }
        if (cmd.emergencyOverride() && !"CRITICAL_FOLLOWUP".equalsIgnoreCase(cmd.templateCode())) {
            throw new SecurityException(
                    "Emergency consent override is restricted to critical follow-up communication");
        }

        String lang = "fr".equalsIgnoreCase(cmd.languageCode()) ? "fr" : "en";
        String body = renderPrivacyMinimizedTemplate(cmd.templateCode(), lang, cmd.templateVars());

        DurableNotificationEntity entity = new DurableNotificationEntity();
        entity.setFacilityId(cmd.facilityId());
        entity.setPatientId(cmd.patientId());
        entity.setEncounterId(cmd.encounterId());
        entity.setChannel(cmd.channel() != null ? cmd.channel() : NotificationChannel.SMS);
        entity.setTemplateCode(cmd.templateCode());
        entity.setLanguageCode(lang);
        entity.setRecipientAddress(cmd.recipientAddress().trim());
        entity.setRenderedMessage(body);
        entity.setIdempotencyKey(idempKey);
        entity.setEmergencyOverride(cmd.emergencyOverride());
        entity.setPrivacyMinimized(true);

        if (cmd.patientOptedOut() && !cmd.emergencyOverride()) {
            entity.setDeliveryState(NotificationDeliveryState.SUPPRESSED_BY_POLICY);
            return notificationRepo.save(entity);
        }

        entity.setDeliveryState(NotificationDeliveryState.QUEUED);
        Optional<NotificationProviderAdapterSpi> adapter = notificationAdapters.stream()
                .filter(a -> a.supports(entity.getChannel()))
                .findFirst();

        if (adapter.isPresent()) {
            entity.setAttemptCount(1);
            try {
                ProviderSubmissionResult res = adapter.get().submit(entity);
                if (res != null && res.accepted()
                        && res.providerReceiptId() != null && !res.providerReceiptId().isBlank()) {
                    entity.setDeliveryState(NotificationDeliveryState.SUBMITTED_TO_PROVIDER);
                    entity.setProviderReceiptId(res.providerReceiptId().trim());
                    entity.setSubmittedAt(Instant.now());
                } else {
                    entity.setDeliveryState(NotificationDeliveryState.RETRYING);
                    // Provider details can contain PHI; retain only a stable, bounded reason.
                    entity.setLastError("NO_PROVIDER_ACCEPTANCE_RECEIPT");
                }
            } catch (RuntimeException providerFailure) {
                // Do not put provider exception messages (which may contain patient data) in the ledger.
                // A real integration must use a committed outbox and provider idempotency keys to
                // handle ambiguous send outcomes; this fallback only avoids false delivery claims.
                entity.setDeliveryState(NotificationDeliveryState.RETRYING);
                entity.setLastError("PROVIDER_SUBMISSION_UNCONFIRMED");
            }
        }
        return notificationRepo.save(entity);
    }

    /** Section 8 — Marks notification {@code DELIVERED} strictly upon real provider delivery receipt. */
    @Transactional
    public DurableNotificationEntity confirmNotificationDeliveryReceipt(UUID facilityId, UUID notificationId, String receiptId) {
        DurableNotificationEntity entity = notificationRepo.findById(notificationId)
                .orElseThrow(() -> new NoSuchElementException("Notification not found: " + notificationId));
        if (!facilityId.equals(entity.getFacilityId())) {
            throw new SecurityException("Cross-facility notification access denied");
        }
        if (receiptId == null || receiptId.isBlank()) {
            throw new IllegalArgumentException("A non-empty provider delivery receipt is required");
        }
        String normalizedReceipt = receiptId.trim();
        if (entity.getDeliveryState() == NotificationDeliveryState.DELIVERED) {
            if (!normalizedReceipt.equals(entity.getProviderReceiptId())) {
                throw new SecurityException("Delivery receipt conflicts with the original confirmed receipt");
            }
            return entity; // Exact retry cannot rewrite a previously confirmed delivery.
        }
        if (entity.getDeliveryState() != NotificationDeliveryState.SUBMITTED_TO_PROVIDER
                || entity.getProviderReceiptId() == null
                || entity.getProviderReceiptId().isBlank()) {
            throw new IllegalStateException("Cannot confirm delivery without a recorded provider submission receipt");
        }
        entity.setDeliveryState(NotificationDeliveryState.DELIVERED);
        entity.setProviderReceiptId(normalizedReceipt);
        entity.setDeliveredAt(Instant.now());
        return notificationRepo.save(entity);
    }

    /**
     * Section 3 &amp; Test F — Verifies an encounter cannot be prematurely finalized while parallel
     * active orders or unacknowledged critical clinician-inbox results remain open.
     */
    public EncounterFinalizationCheck verifyCanFinalizeEncounter(UUID facilityId, UUID encounterId) {
        List<String> blockers = new ArrayList<>();
        List<DepartmentOrderRoutingEntity> routed = routingRepo.findByFacilityIdAndEncounterId(facilityId, encounterId);
        for (DepartmentOrderRoutingEntity r : routed) {
            if (r.getRoutingStatus() != OrderRoutingStatus.COMPLETED && r.getRoutingStatus() != OrderRoutingStatus.CANCELLED) {
                blockers.add("Order " + r.getOrderId() + " (" + r.getTargetDepartment() + ") is still " + r.getRoutingStatus());
            }
        }
        List<ClinicianResultInboxEntity> inboxItems = inboxRepo.findByFacilityIdAndEncounterIdOrderByReceivedAtAsc(facilityId, encounterId);
        for (ClinicianResultInboxEntity item : inboxItems) {
            if (item.getAcknowledgedAt() == null
                    && (item.isCriticalFlag() || item.getInboxStatus() == ClinicianInboxStatus.ACTION_REQUIRED)) {
                blockers.add("Clinician inbox item " + item.getId() + " requires explicit acknowledgment (" + item.getInboxStatus() + ")");
            }
        }
        return new EncounterFinalizationCheck(blockers.isEmpty(), List.copyOf(blockers));
    }

    public List<ClinicianResultInboxEntity> getClinicianInbox(UUID facilityId, String clinicianId) {
        return inboxRepo.findByFacilityIdAndResponsibleClinicianIdOrderByCriticalFlagDescReceivedAtAsc(facilityId, clinicianId);
    }

    private void emitCanonicalEvent(CanonicalDomainEventEnvelope env) {
        if (outboxRepo == null) {
            return;
        }
        TransactionalOutboxEntity evt = new TransactionalOutboxEntity();
        evt.setIdempotencyKey(env.idempotencyKey() + "-" + env.eventId());
        evt.setFacilityId(env.facilityId());
        evt.setAggregateType("PatientJourney");
        evt.setAggregateId(env.encounterId() != null ? env.encounterId() : env.eventId());
        evt.setEventType(env.eventType().name());
        try {
            evt.setPayloadJson(objectMapper.writeValueAsString(env));
        } catch (Exception serializationFailure) {
            throw new IllegalStateException("Cannot serialize patient-journey event; transaction must roll back", serializationFailure);
        }
        outboxRepo.save(evt);
    }

    private static String renderPrivacyMinimizedTemplate(String templateCode, String lang, Map<String, String> vars) {
        boolean fr = "fr".equalsIgnoreCase(lang);
        Map<String, String> v = vars != null ? vars : Map.of();
        String facility = v.getOrDefault("facility", "your healthcare facility");
        return switch (templateCode != null ? templateCode.toUpperCase(Locale.ROOT) : "GENERAL") {
            case "RESULT_READY" -> fr
                    ? "Vos resultats sont disponibles a " + facility + ". Veuillez consulter votre medecin."
                    : "Your results are ready at " + facility + ". Please review them with your clinician.";
            case "MEDICATION_READY" -> fr
                    ? "Votre ordonnance est prete pour retrait a la pharmacie de " + facility + "."
                    : "Your prescription is ready for pickup at " + facility + ".";
            case "CRITICAL_FOLLOWUP" -> fr
                    ? "ALERTE CLINIQUE: Veuillez contacter immediatement " + facility + "."
                    : "URGENT CLINICAL NOTICE: Please contact " + facility + " immediately.";
            default -> fr
                    ? "Mise a jour de votre parcours de soins a " + facility + "."
                    : "Care journey update from " + facility + ".";
        };
    }
}
