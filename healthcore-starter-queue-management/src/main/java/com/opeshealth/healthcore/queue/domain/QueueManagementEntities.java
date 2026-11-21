/*
 * =============================================================================
 * Opes HealthCore SDK — Departmental Queue Management JPA Entities
 * =============================================================================
 */
package com.opeshealth.healthcore.queue.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;
import org.hibernate.annotations.Filter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Multi-tenant JPA entities for reusable departmental queues across reception, triage,
 * nursing, billing/cashier, consultation, laboratory, radiology, pharmacy, procedures,
 * admissions, and discharge.
 *
 * <p>Stores canonical foreign references ({@code patientId}, {@code encounterId},
 * {@code orderId}, {@code taskId}, {@code workflowInstanceId}) without duplicating
 * patient demographics.</p>
 */
public final class QueueManagementEntities {

    private QueueManagementEntities() {}

    public enum QueueStatus {
        WAITING,
        READY,
        CALLED,
        IN_SERVICE,
        ON_HOLD,
        COMPLETED,
        SKIPPED,
        NO_SHOW,
        CANCELLED,
        TRANSFERRED
    }

    public enum QueuePriority {
        STAT,
        URGENT,
        ROUTINE
    }

    /**
     * Concurrency-safe per-facility, per-department, per-day monotonic ticket counter.
     * Eliminates race conditions inherent in {@code MAX(ticket_number) + 1} queries.
     */
    @Entity(name = "QueueManagementCounter")
    @Table(name = "hc_queue_counters",
           uniqueConstraints = @UniqueConstraint(
                   name = "uq_hc_queue_counter_scope",
                   columnNames = {"facility_id", "department_code", "service_date"}))
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class QueueCounterEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "department_code", nullable = false, length = 64)
        private String departmentCode;

        @Column(name = "service_date", nullable = false)
        private LocalDate serviceDate;

        @Column(name = "last_ticket_seq", nullable = false)
        private long lastTicketSeq = 0L;

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getDepartmentCode() { return departmentCode; }
        public void setDepartmentCode(String departmentCode) { this.departmentCode = departmentCode; }
        public LocalDate getServiceDate() { return serviceDate; }
        public void setServiceDate(LocalDate serviceDate) { this.serviceDate = serviceDate; }
        public long getLastTicketSeq() { return lastTicketSeq; }
        public void setLastTicketSeq(long lastTicketSeq) { this.lastTicketSeq = lastTicketSeq; }
        public long getVersion() { return version; }
    }

    /**
     * Operational queue entry tracking position, urgency, atomic worker assignment,
     * separate financial clearance state, SLA breaches, and re-entry history.
     */
    @Entity
    @Table(name = "hc_department_queue_entries",
           uniqueConstraints = @UniqueConstraint(
                   name = "uq_hc_queue_idempotency",
                   columnNames = {"facility_id", "idempotency_key"}))
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class DepartmentQueueEntryEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "department_code", nullable = false, length = 64)
        private String departmentCode;

        @Column(name = "ticket_number", nullable = false, length = 32)
        private String ticketNumber;

        @Column(name = "ticket_sequence", nullable = false)
        private long ticketSequence;

        @Column(name = "idempotency_key", nullable = false, length = 160)
        private String idempotencyKey;

        @Column(name = "patient_id")
        private UUID patientId;

        @Column(name = "encounter_id")
        private UUID encounterId;

        @Column(name = "order_id")
        private UUID orderId;

        @Column(name = "task_id")
        private UUID taskId;

        @Column(name = "workflow_instance_id")
        private UUID workflowInstanceId;

        @Enumerated(EnumType.STRING)
        @Column(name = "queue_status", nullable = false, length = 24)
        private QueueStatus queueStatus = QueueStatus.WAITING;

        @Enumerated(EnumType.STRING)
        @Column(name = "priority", nullable = false, length = 16)
        private QueuePriority priority = QueuePriority.ROUTINE;

        @Column(name = "urgency_score", nullable = false)
        private int urgencyScore = 50;

        /** Separate financial clearance status (never conflated with operational queue_status). */
        @Column(name = "financial_clearance_state", nullable = false, length = 32)
        private String financialClearanceState = "PAYMENT_NOT_REQUIRED";

        @Column(name = "eligible_for_activation", nullable = false)
        private boolean eligibleForActivation = true;

        @Column(name = "assigned_staff_id", length = 120)
        private String assignedStaffId;

        @Column(name = "claimed_by", length = 120)
        private String claimedBy;

        @Column(name = "claimed_at")
        private Instant claimedAt;

        @Column(name = "check_in_at", nullable = false)
        private Instant checkInAt = Instant.now();

        @Column(name = "called_at")
        private Instant calledAt;

        @Column(name = "service_started_at")
        private Instant serviceStartedAt;

        @Column(name = "completed_at")
        private Instant completedAt;

        @Column(name = "sla_deadline_at", nullable = false)
        private Instant slaDeadlineAt;

        @Column(name = "sla_breached", nullable = false)
        private boolean slaBreached = false;

        @Column(name = "reentry_count", nullable = false)
        private int reentryCount = 0;

        @Column(name = "hold_reason", length = 240)
        private String holdReason;

        @Column(name = "transfer_target_department", length = 64)
        private String transferTargetDepartment;

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getDepartmentCode() { return departmentCode; }
        public void setDepartmentCode(String departmentCode) { this.departmentCode = departmentCode; }
        public String getTicketNumber() { return ticketNumber; }
        public void setTicketNumber(String ticketNumber) { this.ticketNumber = ticketNumber; }
        public long getTicketSequence() { return ticketSequence; }
        public void setTicketSequence(long ticketSequence) { this.ticketSequence = ticketSequence; }
        public String getIdempotencyKey() { return idempotencyKey; }
        public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public UUID getEncounterId() { return encounterId; }
        public void setEncounterId(UUID encounterId) { this.encounterId = encounterId; }
        public UUID getOrderId() { return orderId; }
        public void setOrderId(UUID orderId) { this.orderId = orderId; }
        public UUID getTaskId() { return taskId; }
        public void setTaskId(UUID taskId) { this.taskId = taskId; }
        public UUID getWorkflowInstanceId() { return workflowInstanceId; }
        public void setWorkflowInstanceId(UUID workflowInstanceId) { this.workflowInstanceId = workflowInstanceId; }
        public QueueStatus getQueueStatus() { return queueStatus; }
        public void setQueueStatus(QueueStatus queueStatus) { this.queueStatus = queueStatus; }
        public QueuePriority getPriority() { return priority; }
        public void setPriority(QueuePriority priority) { this.priority = priority; }
        public int getUrgencyScore() { return urgencyScore; }
        public void setUrgencyScore(int urgencyScore) { this.urgencyScore = urgencyScore; }
        public String getFinancialClearanceState() { return financialClearanceState; }
        public void setFinancialClearanceState(String financialClearanceState) { this.financialClearanceState = financialClearanceState; }
        public boolean isEligibleForActivation() { return eligibleForActivation; }
        public void setEligibleForActivation(boolean eligibleForActivation) { this.eligibleForActivation = eligibleForActivation; }
        public String getAssignedStaffId() { return assignedStaffId; }
        public void setAssignedStaffId(String assignedStaffId) { this.assignedStaffId = assignedStaffId; }
        public String getClaimedBy() { return claimedBy; }
        public void setClaimedBy(String claimedBy) { this.claimedBy = claimedBy; }
        public Instant getClaimedAt() { return claimedAt; }
        public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }
        public Instant getCheckInAt() { return checkInAt; }
        public void setCheckInAt(Instant checkInAt) { this.checkInAt = checkInAt; }
        public Instant getCalledAt() { return calledAt; }
        public void setCalledAt(Instant calledAt) { this.calledAt = calledAt; }
        public Instant getServiceStartedAt() { return serviceStartedAt; }
        public void setServiceStartedAt(Instant serviceStartedAt) { this.serviceStartedAt = serviceStartedAt; }
        public Instant getCompletedAt() { return completedAt; }
        public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
        public Instant getSlaDeadlineAt() { return slaDeadlineAt; }
        public void setSlaDeadlineAt(Instant slaDeadlineAt) { this.slaDeadlineAt = slaDeadlineAt; }
        public boolean isSlaBreached() { return slaBreached; }
        public void setSlaBreached(boolean slaBreached) { this.slaBreached = slaBreached; }
        public int getReentryCount() { return reentryCount; }
        public void setReentryCount(int reentryCount) { this.reentryCount = reentryCount; }
        public String getHoldReason() { return holdReason; }
        public void setHoldReason(String holdReason) { this.holdReason = holdReason; }
        public String getTransferTargetDepartment() { return transferTargetDepartment; }
        public void setTransferTargetDepartment(String transferTargetDepartment) { this.transferTargetDepartment = transferTargetDepartment; }
        public long getVersion() { return version; }
    }

    /**
     * Operational pause/resume control state per facility and department queue.
     */
    @Entity
    @Table(name = "hc_queue_pause_controls",
           uniqueConstraints = @UniqueConstraint(
                   name = "uq_hc_queue_pause_dept",
                   columnNames = {"facility_id", "department_code"}))
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class QueuePauseControlEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "department_code", nullable = false, length = 64)
        private String departmentCode;

        @Column(name = "paused", nullable = false)
        private boolean paused = false;

        @Column(name = "pause_reason", length = 240)
        private String pauseReason;

        @Column(name = "updated_by", length = 120)
        private String updatedBy;

        @Column(name = "updated_at", nullable = false)
        private Instant updatedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getDepartmentCode() { return departmentCode; }
        public void setDepartmentCode(String departmentCode) { this.departmentCode = departmentCode; }
        public boolean isPaused() { return paused; }
        public void setPaused(boolean paused) { this.paused = paused; }
        public String getPauseReason() { return pauseReason; }
        public void setPauseReason(String pauseReason) { this.pauseReason = pauseReason; }
        public String getUpdatedBy() { return updatedBy; }
        public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
        public Instant getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    }
}
