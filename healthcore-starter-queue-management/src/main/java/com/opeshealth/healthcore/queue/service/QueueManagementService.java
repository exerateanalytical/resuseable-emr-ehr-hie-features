/*
 * =============================================================================
 * Opes HealthCore SDK — Departmental Queue Management Service & REST Gateway
 * =============================================================================
 */
package com.opeshealth.healthcore.queue.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.queue.domain.QueueManagementEntities.*;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Concurrency-safe, multi-tenant departmental queue engine supporting reception, triage,
 * nursing, cashier/billing, consultation, laboratory collection/processing, radiology,
 * pharmacy, procedures, admissions, discharge, and custom facility departments.
 */
public class QueueManagementService {

    public static final Set<String> ACTIVATION_CLEARED_STATES = Set.of(
            "PAYMENT_NOT_REQUIRED", "PAID", "INSURANCE_AUTHORIZED",
            "CREDIT_APPROVED", "SPONSORED", "DEFERRED", "WAIVED", "EMERGENCY_OVERRIDE"
    );

    public interface QueueCounterRepository extends JpaRepository<QueueCounterEntity, UUID> {
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select c from QueueCounterEntity c where c.facilityId = :facilityId and c.departmentCode = :dept and c.serviceDate = :serviceDate")
        Optional<QueueCounterEntity> findForUpdate(
                @Param("facilityId") UUID facilityId,
                @Param("dept") String departmentCode,
                @Param("serviceDate") LocalDate serviceDate);
    }

    public interface DepartmentQueueEntryRepository extends JpaRepository<DepartmentQueueEntryEntity, UUID> {
        Optional<DepartmentQueueEntryEntity> findByFacilityIdAndIdempotencyKey(UUID facilityId, String idempotencyKey);
        List<DepartmentQueueEntryEntity> findByFacilityIdAndDepartmentCodeOrderByUrgencyScoreDescCheckInAtAsc(UUID facilityId, String departmentCode);
        List<DepartmentQueueEntryEntity> findByFacilityIdAndPatientIdOrderByCheckInAtDesc(UUID facilityId, UUID patientId);
        List<DepartmentQueueEntryEntity> findByFacilityIdAndEncounterIdOrderByCheckInAtAsc(UUID facilityId, UUID encounterId);
        List<DepartmentQueueEntryEntity> findByFacilityIdAndOrderId(UUID facilityId, UUID orderId);
        List<DepartmentQueueEntryEntity> findBySlaBreachedFalseAndCompletedAtIsNullAndSlaDeadlineAtBefore(Instant cutoff);
    }

    public interface QueuePauseControlRepository extends JpaRepository<QueuePauseControlEntity, UUID> {
        Optional<QueuePauseControlEntity> findByFacilityIdAndDepartmentCode(UUID facilityId, String departmentCode);
    }

    /** Pluggable prioritization SPI allowing facilities to customize queue scoring rules. */
    @FunctionalInterface
    public interface QueuePrioritizationSpi {
        int computeUrgencyScore(QueuePriority priority, String departmentCode, int reentryCount, boolean emergencyOverride);
    }

    /** Pluggable real-time queue event subscriber SPI (e.g., waiting room displays, WebSockets, SSE). */
    @FunctionalInterface
    public interface QueueEventSubscriptionSpi {
        void onQueueEvent(QueueEventPayload event);
    }

    public record QueueEventPayload(
            UUID eventId,
            String eventType,
            UUID facilityId,
            String departmentCode,
            UUID queueEntryId,
            String ticketNumber,
            QueueStatus status,
            Instant occurredAt
    ) {}

    public record EnqueueRequest(
            String departmentCode,
            UUID patientId,
            UUID encounterId,
            UUID orderId,
            UUID taskId,
            UUID workflowInstanceId,
            QueuePriority priority,
            String financialClearanceState,
            String assignedStaffId,
            Integer slaMinutes,
            String idempotencyKey
    ) {}

    /** Privacy-minimized patient-facing status (zero clinical diagnosis or sensitive PHI). */
    public record PatientFacingQueueStatus(
            String ticketNumber,
            String departmentCode,
            QueueStatus status,
            int positionAhead,
            long estimatedWaitMinutes,
            boolean queuePaused
    ) {}

    private final QueueCounterRepository counterRepo;
    private final DepartmentQueueEntryRepository entryRepo;
    private final QueuePauseControlRepository pauseRepo;
    private final OutboxRelayService.OutboxRepository outboxRepo;
    private final QueuePrioritizationSpi prioritizationSpi;
    private final List<QueueEventSubscriptionSpi> subscribers;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, ReentrantLock> inProcessLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> entryClaimLocks = new ConcurrentHashMap<>();

    public QueueManagementService(
            QueueCounterRepository counterRepo,
            DepartmentQueueEntryRepository entryRepo,
            QueuePauseControlRepository pauseRepo,
            OutboxRelayService.OutboxRepository outboxRepo,
            QueuePrioritizationSpi prioritizationSpi,
            List<QueueEventSubscriptionSpi> subscribers) {
        this.counterRepo = counterRepo;
        this.entryRepo = entryRepo;
        this.pauseRepo = pauseRepo;
        this.outboxRepo = outboxRepo;
        this.prioritizationSpi = prioritizationSpi != null ? prioritizationSpi : QueueManagementService::defaultUrgencyScore;
        this.subscribers = subscribers != null ? List.copyOf(subscribers) : List.of();
    }

    public static int defaultUrgencyScore(QueuePriority priority, String dept, int reentryCount, boolean emergencyOverride) {
        int base = switch (priority != null ? priority : QueuePriority.ROUTINE) {
            case STAT -> 100;
            case URGENT -> 75;
            case ROUTINE -> 40;
        };
        if (emergencyOverride) {
            base = Math.max(base, 110);
        }
        return base + Math.min(20, reentryCount * 10);
    }

    /**
     * Concurrency-safe, idempotent enqueue operation allocating a strictly unique daily ticket
     * number per facility and department. Replaying the same {@code idempotencyKey} returns the
     * existing active entry without creating duplicates.
     */
    @Transactional
    @ClinicalAudit(action = "QUEUE_ENQUEUE", resourceType = "DepartmentQueueEntry")
    public DepartmentQueueEntryEntity enqueue(UUID facilityId, EnqueueRequest req, String actorId) {
        Objects.requireNonNull(facilityId, "facilityId is required");
        Objects.requireNonNull(req, "EnqueueRequest is required");
        String dept = normalizeDept(req.departmentCode());
        String idempotencyKey = (req.idempotencyKey() != null && !req.idempotencyKey().isBlank())
                ? req.idempotencyKey().trim()
                : buildDefaultIdempotencyKey(dept, req.orderId(), req.encounterId(), req.taskId());

        Optional<DepartmentQueueEntryEntity> existing = entryRepo.findByFacilityIdAndIdempotencyKey(facilityId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        long seq = allocateNextSequenceAtomic(facilityId, dept, today);
        String prefix = dept.length() >= 3 ? dept.substring(0, 3) : dept;
        String ticketNumber = prefix + "-" + String.format("%04d", seq);

        String clearance = (req.financialClearanceState() != null && !req.financialClearanceState().isBlank())
                ? req.financialClearanceState().trim().toUpperCase(Locale.ROOT)
                : "PAYMENT_NOT_REQUIRED";
        boolean emergencyOverride = "EMERGENCY_OVERRIDE".equals(clearance);
        boolean eligible = ACTIVATION_CLEARED_STATES.contains(clearance);
        QueuePriority priority = req.priority() != null ? req.priority() : QueuePriority.ROUTINE;

        int slaMin = req.slaMinutes() != null && req.slaMinutes() > 0
                ? req.slaMinutes()
                : (priority == QueuePriority.STAT ? 15 : (priority == QueuePriority.URGENT ? 45 : 90));

        DepartmentQueueEntryEntity entry = new DepartmentQueueEntryEntity();
        entry.setFacilityId(facilityId);
        entry.setDepartmentCode(dept);
        entry.setTicketNumber(ticketNumber);
        entry.setTicketSequence(seq);
        entry.setIdempotencyKey(idempotencyKey);
        entry.setPatientId(req.patientId());
        entry.setEncounterId(req.encounterId());
        entry.setOrderId(req.orderId());
        entry.setTaskId(req.taskId());
        entry.setWorkflowInstanceId(req.workflowInstanceId());
        entry.setPriority(priority);
        entry.setFinancialClearanceState(clearance);
        entry.setEligibleForActivation(eligible);
        entry.setQueueStatus(eligible ? QueueStatus.READY : QueueStatus.WAITING);
        entry.setUrgencyScore(prioritizationSpi.computeUrgencyScore(priority, dept, 0, emergencyOverride));
        entry.setAssignedStaffId(req.assignedStaffId());
        entry.setCheckInAt(Instant.now());
        entry.setSlaDeadlineAt(entry.getCheckInAt().plus(Duration.ofMinutes(slaMin)));

        DepartmentQueueEntryEntity saved = entryRepo.save(entry);
        publishEvent("QueueEntryCreated", saved);
        return saved;
    }

    /**
     * Atomically claims a specific eligible queue entry for a worker, rejecting concurrent or
     * conflicting claims cleanly and preventing activation of financially ineligible non-emergency tasks.
     */
    @Transactional
    @ClinicalAudit(action = "QUEUE_CLAIM_TASK", resourceType = "DepartmentQueueEntry")
    public DepartmentQueueEntryEntity claimTask(UUID facilityId, UUID queueEntryId, String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId is required to claim a queue task");
        }
        Object lock = entryClaimLocks.computeIfAbsent(queueEntryId, k -> new Object());
        synchronized (lock) {
            DepartmentQueueEntryEntity entry = requireOwnedEntry(facilityId, queueEntryId);
            assertQueueNotPaused(facilityId, entry.getDepartmentCode());
            if (!entry.isEligibleForActivation()) {
                throw new IllegalStateException("Queue entry " + entry.getTicketNumber()
                        + " is awaiting prerequisite/financial clearance: " + entry.getFinancialClearanceState());
            }
            if (entry.getClaimedBy() != null && !entry.getClaimedBy().equals(workerId)) {
                throw new IllegalStateException("Queue entry " + entry.getTicketNumber()
                        + " is already atomically claimed by " + entry.getClaimedBy());
            }
            if (entry.getQueueStatus() == QueueStatus.COMPLETED || entry.getQueueStatus() == QueueStatus.CANCELLED) {
                throw new IllegalStateException("Cannot claim finalized queue entry in status " + entry.getQueueStatus());
            }
            entry.setClaimedBy(workerId);
            entry.setClaimedAt(Instant.now());
            entry.setAssignedStaffId(workerId);
            entry.setQueueStatus(QueueStatus.IN_SERVICE);
            if (entry.getServiceStartedAt() == null) {
                entry.setServiceStartedAt(Instant.now());
            }
            DepartmentQueueEntryEntity saved = entryRepo.save(entry);
            publishEvent("QueueEntryClaimed", saved);
            return saved;
        }
    }

    @Transactional
    @ClinicalAudit(action = "QUEUE_CALL_PATIENT", resourceType = "DepartmentQueueEntry")
    public DepartmentQueueEntryEntity callEntry(UUID facilityId, UUID queueEntryId, String staffId) {
        DepartmentQueueEntryEntity entry = requireOwnedEntry(facilityId, queueEntryId);
        assertQueueNotPaused(facilityId, entry.getDepartmentCode());
        if (!entry.isEligibleForActivation()) {
            throw new IllegalStateException("Queue entry " + entry.getTicketNumber() + " is not yet eligible for activation");
        }
        entry.setQueueStatus(QueueStatus.CALLED);
        entry.setCalledAt(Instant.now());
        if (staffId != null && !staffId.isBlank()) {
            entry.setAssignedStaffId(staffId);
        }
        DepartmentQueueEntryEntity saved = entryRepo.save(entry);
        publishEvent("QueueEntryCalled", saved);
        return saved;
    }

    @Transactional
    @ClinicalAudit(action = "QUEUE_COMPLETE_ENTRY", resourceType = "DepartmentQueueEntry")
    public DepartmentQueueEntryEntity completeEntry(UUID facilityId, UUID queueEntryId, String actorId) {
        DepartmentQueueEntryEntity entry = requireOwnedEntry(facilityId, queueEntryId);
        if (entry.getQueueStatus() == QueueStatus.COMPLETED) {
            return entry; // Replay preserves the original time and does not double-publish.
        }
        if (!entry.isEligibleForActivation()) {
            throw new IllegalStateException("Cannot complete a task while financial or clinical hold is active");
        }
        if (actorId == null || actorId.isBlank()
                || !actorId.equals(entry.getClaimedBy())
                || entry.getQueueStatus() != QueueStatus.IN_SERVICE) {
            throw new SecurityException(
                    "Only the assigned worker may complete a claimed in-service queue task");
        }
        entry.setQueueStatus(QueueStatus.COMPLETED);
        entry.setCompletedAt(Instant.now());
        DepartmentQueueEntryEntity saved = entryRepo.save(entry);
        publishEvent("QueueEntryCompleted", saved);
        return saved;
    }

    @Transactional
    @ClinicalAudit(action = "QUEUE_UPDATE_CLEARANCE", resourceType = "DepartmentQueueEntry")
    public DepartmentQueueEntryEntity updateFinancialClearance(UUID facilityId, UUID queueEntryId, String clearanceState) {
        DepartmentQueueEntryEntity entry = requireOwnedEntry(facilityId, queueEntryId);
        if (entry.getQueueStatus() == QueueStatus.COMPLETED
                || entry.getQueueStatus() == QueueStatus.CANCELLED) {
            throw new IllegalStateException("Cannot modify financial clearance after queue closure");
        }
        if (clearanceState == null || clearanceState.isBlank()) {
            throw new IllegalArgumentException("Explicit financial clearance state required");
        }
        String normalized = clearanceState.trim().toUpperCase(Locale.ROOT);
        entry.setFinancialClearanceState(normalized);
        boolean eligible = ACTIVATION_CLEARED_STATES.contains(normalized);
        entry.setEligibleForActivation(eligible);
        if (eligible && (entry.getQueueStatus() == QueueStatus.WAITING || entry.getQueueStatus() == QueueStatus.ON_HOLD)) {
            entry.setQueueStatus(QueueStatus.READY);
            entry.setHoldReason(null);
        } else if (!eligible && entry.getQueueStatus() == QueueStatus.READY) {
            entry.setQueueStatus(QueueStatus.WAITING);
        }
        if ("EMERGENCY_OVERRIDE".equals(normalized)) {
            entry.setPriority(QueuePriority.STAT);
            entry.setUrgencyScore(prioritizationSpi.computeUrgencyScore(QueuePriority.STAT, entry.getDepartmentCode(), entry.getReentryCount(), true));
        }
        DepartmentQueueEntryEntity saved = entryRepo.save(entry);
        publishEvent(eligible ? "ServiceCleared" : "FinancialClearanceRequired", saved);
        return saved;
    }

    @Transactional
    @ClinicalAudit(action = "QUEUE_HOLD_OR_SKIP", resourceType = "DepartmentQueueEntry")
    public DepartmentQueueEntryEntity transitionOperationalStatus(
            UUID facilityId, UUID queueEntryId, QueueStatus targetStatus, String reason) {
        DepartmentQueueEntryEntity entry = requireOwnedEntry(facilityId, queueEntryId);
        if (targetStatus == null || targetStatus == QueueStatus.COMPLETED) {
            throw new IllegalArgumentException("Use completeEntry for accountable work completion");
        }
        if (entry.getQueueStatus() == QueueStatus.COMPLETED
                || entry.getQueueStatus() == QueueStatus.CANCELLED) {
            throw new IllegalStateException("Finalized work cannot transition to another queue status");
        }
        entry.setQueueStatus(targetStatus);
        if (reason != null) {
            entry.setHoldReason(reason);
        }
        if (targetStatus == QueueStatus.NO_SHOW || targetStatus == QueueStatus.SKIPPED || targetStatus == QueueStatus.ON_HOLD) {
            entry.setClaimedBy(null);
            entry.setClaimedAt(null);
        }
        DepartmentQueueEntryEntity saved = entryRepo.save(entry);
        publishEvent("QueueStatusChanged_" + targetStatus.name(), saved);
        return saved;
    }

    @Transactional
    @ClinicalAudit(action = "QUEUE_RECALL", resourceType = "DepartmentQueueEntry")
    public DepartmentQueueEntryEntity recallEntry(UUID facilityId, UUID queueEntryId, String staffId) {
        DepartmentQueueEntryEntity entry = requireOwnedEntry(facilityId, queueEntryId);
        entry.setQueueStatus(QueueStatus.CALLED);
        entry.setCalledAt(Instant.now());
        entry.setHoldReason(null);
        if (staffId != null && !staffId.isBlank()) {
            entry.setAssignedStaffId(staffId);
        }
        DepartmentQueueEntryEntity saved = entryRepo.save(entry);
        publishEvent("QueueEntryRecalled", saved);
        return saved;
    }

    @Transactional
    @ClinicalAudit(action = "QUEUE_TRANSFER", resourceType = "DepartmentQueueEntry")
    public DepartmentQueueEntryEntity transferToDepartment(
            UUID facilityId, UUID queueEntryId, String targetDepartment, String actorId) {
        DepartmentQueueEntryEntity existing = requireOwnedEntry(facilityId, queueEntryId);
        String targetDept = normalizeDept(targetDepartment);
        existing.setQueueStatus(QueueStatus.TRANSFERRED);
        existing.setTransferTargetDepartment(targetDept);
        existing.setCompletedAt(Instant.now());
        entryRepo.save(existing);

        return enqueue(facilityId, new EnqueueRequest(
                targetDept, existing.getPatientId(), existing.getEncounterId(), existing.getOrderId(),
                existing.getTaskId(), existing.getWorkflowInstanceId(), existing.getPriority(),
                existing.getFinancialClearanceState(), null, null,
                "TRANSFER-" + existing.getId() + "-" + targetDept
        ), actorId);
    }

    /**
     * Re-enters a patient into a consultation or follow-up queue after laboratory/imaging
     * investigations complete, elevating priority automatically via {@link QueuePrioritizationSpi}.
     */
    @Transactional
    @ClinicalAudit(action = "QUEUE_REENTRY_AFTER_RESULTS", resourceType = "DepartmentQueueEntry")
    public DepartmentQueueEntryEntity reenterAfterInvestigations(
            UUID facilityId, UUID encounterId, UUID patientId, String targetDepartment,
            String preferredClinicianId, String idempotencyKey) {
        String dept = normalizeDept(targetDepartment);
        String key = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyKey
                : "REENTRY-" + encounterId + "-" + dept;
        Optional<DepartmentQueueEntryEntity> existing = entryRepo.findByFacilityIdAndIdempotencyKey(facilityId, key);
        if (existing.isPresent()) {
            return existing.get();
        }
        DepartmentQueueEntryEntity created = enqueue(facilityId, new EnqueueRequest(
                dept, patientId, encounterId, null, null, null,
                QueuePriority.URGENT, "PAYMENT_NOT_REQUIRED", preferredClinicianId, 30, key
        ), "WORKFLOW_ENGINE");
        created.setReentryCount(created.getReentryCount() + 1);
        created.setUrgencyScore(prioritizationSpi.computeUrgencyScore(
                created.getPriority(), dept, created.getReentryCount(), false));
        return entryRepo.save(created);
    }

    @Transactional
    @ClinicalAudit(action = "QUEUE_PAUSE_CONTROL", resourceType = "QueuePauseControl")
    public QueuePauseControlEntity setQueuePaused(UUID facilityId, String departmentCode, boolean paused, String reason, String actorId) {
        String dept = normalizeDept(departmentCode);
        QueuePauseControlEntity ctrl = pauseRepo.findByFacilityIdAndDepartmentCode(facilityId, dept)
                .orElseGet(() -> {
                    QueuePauseControlEntity c = new QueuePauseControlEntity();
                    c.setFacilityId(facilityId);
                    c.setDepartmentCode(dept);
                    return c;
                });
        ctrl.setPaused(paused);
        ctrl.setPauseReason(paused ? reason : null);
        ctrl.setUpdatedBy(actorId);
        ctrl.setUpdatedAt(Instant.now());
        return pauseRepo.save(ctrl);
    }

    @Transactional
    public int flagSlaBreaches() {
        List<DepartmentQueueEntryEntity> overdue = entryRepo
                .findBySlaBreachedFalseAndCompletedAtIsNullAndSlaDeadlineAtBefore(Instant.now());
        for (DepartmentQueueEntryEntity e : overdue) {
            e.setSlaBreached(true);
            entryRepo.save(e);
            publishEvent("QueueSlaBreached", e);
        }
        return overdue.size();
    }

    public List<DepartmentQueueEntryEntity> getDepartmentWorklist(UUID facilityId, String departmentCode) {
        return entryRepo.findByFacilityIdAndDepartmentCodeOrderByUrgencyScoreDescCheckInAtAsc(
                facilityId, normalizeDept(departmentCode));
    }

    public PatientFacingQueueStatus getPatientFacingStatus(UUID facilityId, UUID queueEntryId) {
        DepartmentQueueEntryEntity entry = requireOwnedEntry(facilityId, queueEntryId);
        List<DepartmentQueueEntryEntity> deptEntries = getDepartmentWorklist(facilityId, entry.getDepartmentCode());
        int ahead = 0;
        long completedSamples = 0;
        long totalServiceSeconds = 0;
        for (DepartmentQueueEntryEntity e : deptEntries) {
            if (e.getQueueStatus() == QueueStatus.COMPLETED && e.getServiceStartedAt() != null && e.getCompletedAt() != null) {
                completedSamples++;
                totalServiceSeconds += Math.max(60, Duration.between(e.getServiceStartedAt(), e.getCompletedAt()).getSeconds());
            }
            if (!e.getId().equals(entry.getId())
                    && (e.getQueueStatus() == QueueStatus.READY || e.getQueueStatus() == QueueStatus.WAITING)
                    && (e.getUrgencyScore() > entry.getUrgencyScore()
                        || (e.getUrgencyScore() == entry.getUrgencyScore() && e.getCheckInAt().isBefore(entry.getCheckInAt())))) {
                ahead++;
            }
        }
        long avgMinutes = completedSamples > 0 ? Math.max(1, (totalServiceSeconds / completedSamples) / 60) : 10L;
        boolean paused = pauseRepo.findByFacilityIdAndDepartmentCode(facilityId, entry.getDepartmentCode())
                .map(QueuePauseControlEntity::isPaused).orElse(false);
        return new PatientFacingQueueStatus(
                entry.getTicketNumber(), entry.getDepartmentCode(), entry.getQueueStatus(),
                ahead, ahead * avgMinutes, paused);
    }

    private long allocateNextSequenceAtomic(UUID facilityId, String dept, LocalDate serviceDate) {
        String lockKey = facilityId + "|" + dept + "|" + serviceDate;
        ReentrantLock lock = inProcessLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        lock.lock();
        try {
            QueueCounterEntity counter = counterRepo.findForUpdate(facilityId, dept, serviceDate)
                    .orElseGet(() -> {
                        QueueCounterEntity c = new QueueCounterEntity();
                        c.setFacilityId(facilityId);
                        c.setDepartmentCode(dept);
                        c.setServiceDate(serviceDate);
                        c.setLastTicketSeq(0L);
                        return c;
                    });
            long next = counter.getLastTicketSeq() + 1L;
            counter.setLastTicketSeq(next);
            counterRepo.save(counter);
            return next;
        } finally {
            lock.unlock();
        }
    }

    private DepartmentQueueEntryEntity requireOwnedEntry(UUID facilityId, UUID queueEntryId) {
        DepartmentQueueEntryEntity entry = entryRepo.findById(queueEntryId)
                .orElseThrow(() -> new NoSuchElementException("Queue entry not found: " + queueEntryId));
        if (!facilityId.equals(entry.getFacilityId())) {
            throw new SecurityException("Cross-facility queue access denied for facility " + facilityId);
        }
        return entry;
    }

    private void assertQueueNotPaused(UUID facilityId, String dept) {
        pauseRepo.findByFacilityIdAndDepartmentCode(facilityId, dept).ifPresent(ctrl -> {
            if (ctrl.isPaused()) {
                throw new IllegalStateException("Department queue " + dept + " is currently paused: " + ctrl.getPauseReason());
            }
        });
    }

    private void publishEvent(String eventType, DepartmentQueueEntryEntity entry) {
        UUID eventId = UuidV7Generator.nextUuid();
        Instant now = Instant.now();
        if (outboxRepo != null) {
            TransactionalOutboxEntity evt = new TransactionalOutboxEntity();
            evt.setIdempotencyKey("Q-" + entry.getId() + "-" + eventType + "-" + eventId);
            evt.setFacilityId(entry.getFacilityId());
            evt.setAggregateType("DepartmentQueueEntry");
            evt.setAggregateId(entry.getId());
            evt.setEventType(eventType);
            try {
                evt.setPayloadJson(objectMapper.writeValueAsString(Map.of(
                        "eventId", eventId.toString(),
                        "queueEntryId", entry.getId().toString(),
                        "departmentCode", entry.getDepartmentCode(),
                        "ticketNumber", entry.getTicketNumber(),
                        "status", entry.getQueueStatus().name(),
                        "financialClearanceState", entry.getFinancialClearanceState()
                )));
            } catch (Exception serializationFailure) {
                throw new IllegalStateException("Cannot serialize queue event; transaction must roll back", serializationFailure);
            }
            outboxRepo.save(evt);
        }
        QueueEventPayload payload = new QueueEventPayload(
                eventId, eventType, entry.getFacilityId(), entry.getDepartmentCode(),
                entry.getId(), entry.getTicketNumber(), entry.getQueueStatus(), now);
        for (QueueEventSubscriptionSpi sub : subscribers) {
            sub.onQueueEvent(payload);
        }
    }

    private static String normalizeDept(String dept) {
        if (dept == null || dept.isBlank()) {
            throw new IllegalArgumentException("departmentCode is required");
        }
        return dept.trim().toUpperCase(Locale.ROOT);
    }

    private static String buildDefaultIdempotencyKey(String dept, UUID orderId, UUID encounterId, UUID taskId) {
        if (orderId != null) return "ORDER-" + orderId + "-" + dept;
        if (taskId != null) return "TASK-" + taskId + "-" + dept;
        if (encounterId != null) return "ENC-" + encounterId + "-" + dept + "-" + UuidV7Generator.nextUuid();
        return "QUEUE-" + dept + "-" + UuidV7Generator.nextUuid();
    }

    @RestController
    @RequestMapping("/api/v1/queues")
    @Tag(name = "Departmental Queue Management", description = "Concurrency-safe departmental queues, atomic worker claiming, and privacy-minimized waiting-room status")
    public static class QueueManagementController {
        private final QueueManagementService service;

        public QueueManagementController(QueueManagementService service) {
            this.service = service;
        }

        @PostMapping("/entries")
        @Operation(summary = "Enqueue a patient or departmental task with atomic ticket allocation")
        @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_facility:manage')")
        public DepartmentQueueEntryEntity enqueue(@RequestBody EnqueueRequest req) {
            return service.enqueue(FacilityContext.requireFacilityId(), req, "API_ACTOR");
        }

        @GetMapping("/departments/{departmentCode}/worklist")
        @Operation(summary = "Retrieve facility-scoped departmental queue worklist ordered by priority and check-in")
        @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_lab:verify') or hasAuthority('PERM_rx:dispense')")
        public List<DepartmentQueueEntryEntity> worklist(@PathVariable String departmentCode) {
            return service.getDepartmentWorklist(FacilityContext.requireFacilityId(), departmentCode);
        }

        @PostMapping("/entries/{queueEntryId}/claim")
        @Operation(summary = "Atomically claim an eligible queue entry for a clinician or technician")
        @PreAuthorize("hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_lab:verify') or hasAuthority('PERM_rx:dispense') or hasAuthority('PERM_facility:manage')")
        public DepartmentQueueEntryEntity claim(@PathVariable UUID queueEntryId, @RequestParam String workerId) {
            return service.claimTask(FacilityContext.requireFacilityId(), queueEntryId, workerId);
        }

        @PostMapping("/entries/{queueEntryId}/call")
        @Operation(summary = "Call an eligible queue entry to the service counter or consultation room")
        @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_facility:manage')")
        public DepartmentQueueEntryEntity call(@PathVariable UUID queueEntryId, @RequestParam(required = false) String staffId) {
            return service.callEntry(FacilityContext.requireFacilityId(), queueEntryId, staffId);
        }

        @PostMapping("/entries/{queueEntryId}/complete")
        @Operation(summary = "Mark a departmental queue entry completed")
        @PreAuthorize("hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_lab:verify') or hasAuthority('PERM_rx:dispense') or hasAuthority('PERM_facility:manage')")
        public DepartmentQueueEntryEntity complete(@PathVariable UUID queueEntryId) {
            return service.completeEntry(FacilityContext.requireFacilityId(), queueEntryId, "API_ACTOR");
        }

        @GetMapping("/entries/{queueEntryId}/patient-status")
        @Operation(summary = "Retrieve privacy-minimized waiting-room status (no clinical diagnosis or sensitive PHI)")
        @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_portal:self')")
        public PatientFacingQueueStatus patientStatus(@PathVariable UUID queueEntryId) {
            return service.getPatientFacingStatus(FacilityContext.requireFacilityId(), queueEntryId);
        }
    }
}
