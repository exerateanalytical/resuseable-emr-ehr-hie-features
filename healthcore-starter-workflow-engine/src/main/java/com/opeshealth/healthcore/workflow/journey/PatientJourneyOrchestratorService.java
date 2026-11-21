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
    private final ObjectMapper objectMapper = new ObjectMapper();

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
        this.workflowService = workflowService;
        this.subflowRepo = subflowRepo;
        this.routingRepo = routingRepo;
        this.inboxRepo = inboxRepo;
        this.notificationRepo = notificationRepo;
        this.processedEventRepo = processedEventRepo;
        this.outboxRepo = outboxRepo;
        this.billingPolicy = billingPolicy != null ? billingPolicy : new DefaultFacilityBillingClearancePolicy();
        this.notificationAdapters = notificationAdapters != null ? List.copyOf(notificationAdapters) : List.of();
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
            return existing.get();
        }
        // The domain maps one clinical order to a single department routing.
        // Replaying the order with a new idempotency key must not create a second task.
        Optional<DepartmentOrderRoutingEntity> previousOrder =
                routingRepo.findByFacilityIdAndOrderId(cmd.facilityId(), cmd.orderId());
        if (previousOrder.isPresent()) {
            verifySameRouteIdentity(previousOrder.get(), cmd);
            return previousOrder.get();
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
        DepartmentOrderRoutingEntity routing = routingRepo.findByFacilityIdAndOrderId(facilityId, orderId)
                .orElseThrow(() -> new NoSuchElementException("Order routing task not found for order " + orderId));
        if (!facilityId.equals(routing.getFacilityId())) {
            throw new SecurityException("Cross-facility payment release denied");
        }
        if (processedEventRepo.findByFacilityIdAndIdempotencyKey(facilityId, paymentIdempotencyKey).isPresent()) {
            return routing; // Idempotent replay: no duplicate state transition or event emission
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
        Optional<ClinicianResultInboxEntity> existing = inboxRepo
                .findByFacilityIdAndResultIdAndResponsibleClinicianId(cmd.facilityId(), cmd.resultId(), cmd.responsibleClinicianId());
        if (existing.isPresent() && !cmd.amendedResult()) {
            return existing.get();
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
        item.setAcknowledgedBy(clinicianId);
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
                    || item.getInboxStatus() == ClinicianInboxStatus.RESOLVED) {
                continue; // Avoid repeat escalation on each SLA scan.
            }
            item.setInboxStatus(ClinicianInboxStatus.ESCALATED);
            if (item.getFallbackClinicianId() != null && !item.getFallbackClinicianId().isBlank()) {
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
        String idempKey = (cmd.idempotencyKey() != null && !cmd.idempotencyKey().isBlank())
                ? cmd.idempotencyKey()
                : "NOTIF-" + cmd.patientId() + "-" + cmd.templateCode() + "-" + UuidV7Generator.nextUuid();

        Optional<DurableNotificationEntity> existing = notificationRepo
                .findByFacilityIdAndIdempotencyKey(cmd.facilityId(), idempKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        if (cmd.recipientAddress() == null || cmd.recipientAddress().isBlank()
                || FORBIDDEN_PLACEHOLDER_RECIPIENTS.contains(cmd.recipientAddress().trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Valid real recipient address is required; placeholder addresses are forbidden");
        }
        if (cmd.containsRawClinicalDiagnosis() && !cmd.emergencyOverride()) {
            throw new IllegalArgumentException("Routine patient notifications must not disclose raw sensitive diagnoses");
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
            ProviderSubmissionResult res = adapter.get().submit(entity);
            if (res.accepted()) {
                entity.setDeliveryState(NotificationDeliveryState.SUBMITTED_TO_PROVIDER);
                entity.setProviderReceiptId(res.providerReceiptId());
                entity.setSubmittedAt(Instant.now());
            } else {
                entity.setDeliveryState(NotificationDeliveryState.RETRYING);
                entity.setLastError(res.errorDetail());
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
        if (entity.getDeliveryState() == NotificationDeliveryState.DELIVERED) {
            return entity; // Duplicate confirmed receipt is idempotent.
        }
        if (entity.getDeliveryState() != NotificationDeliveryState.SUBMITTED_TO_PROVIDER) {
            throw new IllegalStateException("Cannot confirm delivery of notification in state " + entity.getDeliveryState());
        }
        entity.setDeliveryState(NotificationDeliveryState.DELIVERED);
        entity.setProviderReceiptId(receiptId);
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
