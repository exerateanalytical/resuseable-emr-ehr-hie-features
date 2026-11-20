/*
 * =============================================================================
 * Opes HealthCore SDK — Composable Patient Journey, Inbox & Notification Entities
 * =============================================================================
 */
package com.opeshealth.healthcore.workflow.journey;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import com.opeshealth.healthcore.workflow.journey.ClinicalJourneyContracts.PaymentClearanceOutcome;
import jakarta.persistence.*;
import org.hibernate.annotations.Filter;

import java.time.Instant;
import java.util.UUID;

/**
 * Additive multi-tenant JPA entities extending {@code healthcore-starter-workflow-engine}
 * with parent-child workflow composition, automatic departmental order routing, durable
 * clinician results inbox, provider-backed patient notifications, and replay-safe inbox deduplication.
 */
public final class PatientJourneyEntities {

    private PatientJourneyEntities() {}

    public enum OrderTargetDepartment {
        LABORATORY,
        RADIOLOGY,
        PHARMACY,
        EXTERNAL_PHARMACY,
        PROCEDURE,
        REFERRAL
    }

    public enum OrderRoutingStatus {
        ROUTED_AWAITING_CLEARANCE,
        ACTIVE_IN_DEPARTMENT,
        IN_PROGRESS,
        COMPLETED,
        CANCELLED
    }

    /** Section 7 — All 10 clinician results inbox states. */
    public enum ClinicianInboxStatus {
        UNREAD,
        AWAITING_REVIEW,
        REVIEWED,
        ACKNOWLEDGED,
        ACTION_REQUIRED,
        CRITICAL_ESCALATION_PENDING,
        ESCALATED,
        RESOLVED,
        REASSIGNED,
        OVERDUE
    }

    /** Section 8 — All 6 durable patient notification delivery states. */
    public enum NotificationDeliveryState {
        QUEUED,
        SUBMITTED_TO_PROVIDER,
        DELIVERED,
        FAILED,
        RETRYING,
        SUPPRESSED_BY_POLICY
    }

    public enum NotificationChannel {
        SMS,
        WHATSAPP,
        EMAIL,
        PUSH,
        IN_APP
    }

    /** Links a parent outpatient/inpatient journey workflow to independently executing departmental subworkflows. */
    @Entity
    @Table(name = "hc_workflow_subflow_links",
           uniqueConstraints = @UniqueConstraint(
                   name = "uq_hc_wf_subflow_link",
                   columnNames = {"facility_id", "parent_workflow_id", "child_workflow_id"}))
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class WorkflowSubflowLinkEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "parent_workflow_id", nullable = false)
        private UUID parentWorkflowId;

        @Column(name = "child_workflow_id", nullable = false)
        private UUID childWorkflowId;

        @Column(name = "subflow_type", nullable = false, length = 64)
        private String subflowType;

        @Column(name = "encounter_id")
        private UUID encounterId;

        @Column(name = "order_id")
        private UUID orderId;

        @Column(name = "status", nullable = false, length = 24)
        private String status = "ACTIVE";

        @Column(name = "created_at", nullable = false)
        private Instant createdAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getParentWorkflowId() { return parentWorkflowId; }
        public void setParentWorkflowId(UUID parentWorkflowId) { this.parentWorkflowId = parentWorkflowId; }
        public UUID getChildWorkflowId() { return childWorkflowId; }
        public void setChildWorkflowId(UUID childWorkflowId) { this.childWorkflowId = childWorkflowId; }
        public String getSubflowType() { return subflowType; }
        public void setSubflowType(String subflowType) { this.subflowType = subflowType; }
        public UUID getEncounterId() { return encounterId; }
        public void setEncounterId(UUID encounterId) { this.encounterId = encounterId; }
        public UUID getOrderId() { return orderId; }
        public void setOrderId(UUID orderId) { this.orderId = orderId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Instant getCreatedAt() { return createdAt; }
    }

    /** Section 6 — Durable idempotent departmental order routing record (Lab, Radiology, In-House/External Pharmacy). */
    @Entity
    @Table(name = "hc_order_routing_tasks",
           uniqueConstraints = @UniqueConstraint(
                   name = "uq_hc_order_routing_idemp",
                   columnNames = {"facility_id", "idempotency_key"}))
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class DepartmentOrderRoutingEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "patient_id", nullable = false)
        private UUID patientId;

        @Column(name = "encounter_id")
        private UUID encounterId;

        @Column(name = "order_id", nullable = false)
        private UUID orderId;

        @Column(name = "parent_workflow_id")
        private UUID parentWorkflowId;

        @Column(name = "child_workflow_id")
        private UUID childWorkflowId;

        @Column(name = "queue_entry_id")
        private UUID queueEntryId;

        @Enumerated(EnumType.STRING)
        @Column(name = "target_department", nullable = false, length = 32)
        private OrderTargetDepartment targetDepartment;

        @Column(name = "external_pharmacy_code", length = 80)
        private String externalPharmacyCode;

        @Enumerated(EnumType.STRING)
        @Column(name = "financial_clearance", nullable = false, length = 32)
        private PaymentClearanceOutcome financialClearance = PaymentClearanceOutcome.PAYMENT_NOT_REQUIRED;

        @Enumerated(EnumType.STRING)
        @Column(name = "routing_status", nullable = false, length = 32)
        private OrderRoutingStatus routingStatus = OrderRoutingStatus.ACTIVE_IN_DEPARTMENT;

        @Column(name = "idempotency_key", nullable = false, length = 160)
        private String idempotencyKey;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt = Instant.now();

        @Column(name = "completed_at")
        private Instant completedAt;

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public UUID getEncounterId() { return encounterId; }
        public void setEncounterId(UUID encounterId) { this.encounterId = encounterId; }
        public UUID getOrderId() { return orderId; }
        public void setOrderId(UUID orderId) { this.orderId = orderId; }
        public UUID getParentWorkflowId() { return parentWorkflowId; }
        public void setParentWorkflowId(UUID parentWorkflowId) { this.parentWorkflowId = parentWorkflowId; }
        public UUID getChildWorkflowId() { return childWorkflowId; }
        public void setChildWorkflowId(UUID childWorkflowId) { this.childWorkflowId = childWorkflowId; }
        public UUID getQueueEntryId() { return queueEntryId; }
        public void setQueueEntryId(UUID queueEntryId) { this.queueEntryId = queueEntryId; }
        public OrderTargetDepartment getTargetDepartment() { return targetDepartment; }
        public void setTargetDepartment(OrderTargetDepartment targetDepartment) { this.targetDepartment = targetDepartment; }
        public String getExternalPharmacyCode() { return externalPharmacyCode; }
        public void setExternalPharmacyCode(String externalPharmacyCode) { this.externalPharmacyCode = externalPharmacyCode; }
        public PaymentClearanceOutcome getFinancialClearance() { return financialClearance; }
        public void setFinancialClearance(PaymentClearanceOutcome financialClearance) { this.financialClearance = financialClearance; }
        public OrderRoutingStatus getRoutingStatus() { return routingStatus; }
        public void setRoutingStatus(OrderRoutingStatus routingStatus) { this.routingStatus = routingStatus; }
        public String getIdempotencyKey() { return idempotencyKey; }
        public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getCompletedAt() { return completedAt; }
        public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
        public long getVersion() { return version; }
    }

    /** Section 7 — Durable Clinician Results Inbox storing result references (no duplicate clinical payloads). */
    @Entity
    @Table(name = "hc_clinician_result_inbox",
           uniqueConstraints = @UniqueConstraint(
                   name = "uq_hc_clinician_inbox_result",
                   columnNames = {"facility_id", "result_id", "responsible_clinician_id"}))
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class ClinicianResultInboxEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "patient_id", nullable = false)
        private UUID patientId;

        @Column(name = "encounter_id")
        private UUID encounterId;

        @Column(name = "order_id", nullable = false)
        private UUID orderId;

        @Column(name = "result_id", nullable = false)
        private UUID resultId;

        @Column(name = "result_domain", nullable = false, length = 32)
        private String resultDomain;

        @Column(name = "responsible_clinician_id", nullable = false, length = 120)
        private String responsibleClinicianId;

        @Column(name = "care_team_code", length = 64)
        private String careTeamCode;

        @Column(name = "fallback_clinician_id", length = 120)
        private String fallbackClinicianId;

        @Enumerated(EnumType.STRING)
        @Column(name = "inbox_status", nullable = false, length = 32)
        private ClinicianInboxStatus inboxStatus = ClinicianInboxStatus.UNREAD;

        @Column(name = "critical_flag", nullable = false)
        private boolean criticalFlag = false;

        @Column(name = "amended_result", nullable = false)
        private boolean amendedResult = false;

        /** Explicit flag proving electronic notification alone NEVER constitutes clinical acknowledgment. */
        @Column(name = "electronic_notification_sent", nullable = false)
        private boolean electronicNotificationSent = false;

        @Column(name = "grouped_readiness_key", length = 120)
        private String groupedReadinessKey;

        @Column(name = "return_to_queue_required", nullable = false)
        private boolean returnToQueueRequired = false;

        @Column(name = "escalation_deadline_at")
        private Instant escalationDeadlineAt;

        @Column(name = "acknowledged_by", length = 120)
        private String acknowledgedBy;

        @Column(name = "acknowledged_at")
        private Instant acknowledgedAt;

        @Column(name = "acknowledgment_notes", length = 400)
        private String acknowledgmentNotes;

        @Column(name = "received_at", nullable = false)
        private Instant receivedAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public UUID getEncounterId() { return encounterId; }
        public void setEncounterId(UUID encounterId) { this.encounterId = encounterId; }
        public UUID getOrderId() { return orderId; }
        public void setOrderId(UUID orderId) { this.orderId = orderId; }
        public UUID getResultId() { return resultId; }
        public void setResultId(UUID resultId) { this.resultId = resultId; }
        public String getResultDomain() { return resultDomain; }
        public void setResultDomain(String resultDomain) { this.resultDomain = resultDomain; }
        public String getResponsibleClinicianId() { return responsibleClinicianId; }
        public void setResponsibleClinicianId(String responsibleClinicianId) { this.responsibleClinicianId = responsibleClinicianId; }
        public String getCareTeamCode() { return careTeamCode; }
        public void setCareTeamCode(String careTeamCode) { this.careTeamCode = careTeamCode; }
        public String getFallbackClinicianId() { return fallbackClinicianId; }
        public void setFallbackClinicianId(String fallbackClinicianId) { this.fallbackClinicianId = fallbackClinicianId; }
        public ClinicianInboxStatus getInboxStatus() { return inboxStatus; }
        public void setInboxStatus(ClinicianInboxStatus inboxStatus) { this.inboxStatus = inboxStatus; }
        public boolean isCriticalFlag() { return criticalFlag; }
        public void setCriticalFlag(boolean criticalFlag) { this.criticalFlag = criticalFlag; }
        public boolean isAmendedResult() { return amendedResult; }
        public void setAmendedResult(boolean amendedResult) { this.amendedResult = amendedResult; }
        public boolean isElectronicNotificationSent() { return electronicNotificationSent; }
        public void setElectronicNotificationSent(boolean electronicNotificationSent) { this.electronicNotificationSent = electronicNotificationSent; }
        public String getGroupedReadinessKey() { return groupedReadinessKey; }
        public void setGroupedReadinessKey(String groupedReadinessKey) { this.groupedReadinessKey = groupedReadinessKey; }
        public boolean isReturnToQueueRequired() { return returnToQueueRequired; }
        public void setReturnToQueueRequired(boolean returnToQueueRequired) { this.returnToQueueRequired = returnToQueueRequired; }
        public Instant getEscalationDeadlineAt() { return escalationDeadlineAt; }
        public void setEscalationDeadlineAt(Instant escalationDeadlineAt) { this.escalationDeadlineAt = escalationDeadlineAt; }
        public String getAcknowledgedBy() { return acknowledgedBy; }
        public void setAcknowledgedBy(String acknowledgedBy) { this.acknowledgedBy = acknowledgedBy; }
        public Instant getAcknowledgedAt() { return acknowledgedAt; }
        public void setAcknowledgedAt(Instant acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }
        public String getAcknowledgmentNotes() { return acknowledgmentNotes; }
        public void setAcknowledgmentNotes(String acknowledgmentNotes) { this.acknowledgmentNotes = acknowledgmentNotes; }
        public Instant getReceivedAt() { return receivedAt; }
        public long getVersion() { return version; }
    }

    /** Section 8 — Durable, provider-confirmed patient notification ledger (zero placeholder addresses). */
    @Entity
    @Table(name = "hc_durable_notifications",
           uniqueConstraints = @UniqueConstraint(
                   name = "uq_hc_durable_notif_idemp",
                   columnNames = {"facility_id", "idempotency_key"}))
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class DurableNotificationEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "patient_id", nullable = false)
        private UUID patientId;

        @Column(name = "encounter_id")
        private UUID encounterId;

        @Enumerated(EnumType.STRING)
        @Column(name = "channel", nullable = false, length = 24)
        private NotificationChannel channel;

        @Column(name = "template_code", nullable = false, length = 64)
        private String templateCode;

        @Column(name = "language_code", nullable = false, length = 8)
        private String languageCode = "en";

        @Column(name = "recipient_address", nullable = false, length = 180)
        private String recipientAddress;

        @Column(name = "rendered_message", nullable = false, length = 500)
        private String renderedMessage;

        @Enumerated(EnumType.STRING)
        @Column(name = "delivery_state", nullable = false, length = 32)
        private NotificationDeliveryState deliveryState = NotificationDeliveryState.QUEUED;

        @Column(name = "provider_receipt_id", length = 160)
        private String providerReceiptId;

        @Column(name = "idempotency_key", nullable = false, length = 160)
        private String idempotencyKey;

        @Column(name = "attempt_count", nullable = false)
        private int attemptCount = 0;

        @Column(name = "emergency_override", nullable = false)
        private boolean emergencyOverride = false;

        @Column(name = "privacy_minimized", nullable = false)
        private boolean privacyMinimized = true;

        @Column(name = "last_error", length = 300)
        private String lastError;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt = Instant.now();

        @Column(name = "submitted_at")
        private Instant submittedAt;

        @Column(name = "delivered_at")
        private Instant deliveredAt;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public UUID getEncounterId() { return encounterId; }
        public void setEncounterId(UUID encounterId) { this.encounterId = encounterId; }
        public NotificationChannel getChannel() { return channel; }
        public void setChannel(NotificationChannel channel) { this.channel = channel; }
        public String getTemplateCode() { return templateCode; }
        public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }
        public String getLanguageCode() { return languageCode; }
        public void setLanguageCode(String languageCode) { this.languageCode = languageCode; }
        public String getRecipientAddress() { return recipientAddress; }
        public void setRecipientAddress(String recipientAddress) { this.recipientAddress = recipientAddress; }
        public String getRenderedMessage() { return renderedMessage; }
        public void setRenderedMessage(String renderedMessage) { this.renderedMessage = renderedMessage; }
        public NotificationDeliveryState getDeliveryState() { return deliveryState; }
        public void setDeliveryState(NotificationDeliveryState deliveryState) { this.deliveryState = deliveryState; }
        public String getProviderReceiptId() { return providerReceiptId; }
        public void setProviderReceiptId(String providerReceiptId) { this.providerReceiptId = providerReceiptId; }
        public String getIdempotencyKey() { return idempotencyKey; }
        public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
        public int getAttemptCount() { return attemptCount; }
        public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
        public boolean isEmergencyOverride() { return emergencyOverride; }
        public void setEmergencyOverride(boolean emergencyOverride) { this.emergencyOverride = emergencyOverride; }
        public boolean isPrivacyMinimized() { return privacyMinimized; }
        public void setPrivacyMinimized(boolean privacyMinimized) { this.privacyMinimized = privacyMinimized; }
        public String getLastError() { return lastError; }
        public void setLastError(String lastError) { this.lastError = lastError; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getSubmittedAt() { return submittedAt; }
        public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
        public Instant getDeliveredAt() { return deliveredAt; }
        public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }
    }

    /** Section 10 — Transactional inbox deduplication ledger ensuring replay-safe event handling. */
    @Entity
    @Table(name = "hc_workflow_processed_events",
           uniqueConstraints = @UniqueConstraint(
                   name = "uq_hc_wf_processed_evt",
                   columnNames = {"facility_id", "idempotency_key"}))
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class DurableProcessedEventEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "idempotency_key", nullable = false, length = 160)
        private String idempotencyKey;

        @Column(name = "event_type", nullable = false, length = 80)
        private String eventType;

        @Column(name = "processed_at", nullable = false)
        private Instant processedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getIdempotencyKey() { return idempotencyKey; }
        public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
        public String getEventType() { return eventType; }
        public void setEventType(String eventType) { this.eventType = eventType; }
        public Instant getProcessedAt() { return processedAt; }
    }
}
