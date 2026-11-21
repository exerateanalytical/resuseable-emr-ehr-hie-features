/*
 * OPES HealthCore — optional transactional departmental queue integration.
 * The clinical workflow and queue starters stay individually reusable.
 */
package com.opeshealth.healthcore.journeyqueue.auto;

import com.opeshealth.healthcore.queue.autoconfigure.QueueManagementAutoConfiguration;
import com.opeshealth.healthcore.queue.domain.QueueManagementEntities.DepartmentQueueEntryEntity;
import com.opeshealth.healthcore.queue.domain.QueueManagementEntities.QueuePriority;
import com.opeshealth.healthcore.queue.domain.QueueManagementEntities.QueueStatus;
import com.opeshealth.healthcore.queue.service.QueueManagementService;
import com.opeshealth.healthcore.queue.service.QueueManagementService.EnqueueRequest;
import com.opeshealth.healthcore.queue.service.QueueManagementService.DepartmentQueueEntryRepository;
import com.opeshealth.healthcore.workflow.autoconfigure.WorkflowEngineAutoConfiguration;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyEntities.DepartmentOrderRoutingEntity;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyEntities.OrderTargetDepartment;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyOrchestratorService.OrderRoutingQueueProvisioningSpi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.Objects;
import java.util.UUID;

/**
 * Opt-in composition for OPD/HIS consumers: creates queue entries when signed orders
 * are routed; releases the same entries on financial clearance; refuses routing
 * completion before the actual worklist task has been completed.
 *
 * The enclosing workflow transaction commits both routing and queue records, or
 * rolls both back. An external pharmacy is intentionally never queued as an
 * internal hospital dispensary task.
 */
@AutoConfiguration(after = {QueueManagementAutoConfiguration.class, WorkflowEngineAutoConfiguration.class})
public class JourneyQueueBridgeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(OrderRoutingQueueProvisioningSpi.class)
    @ConditionalOnBean(QueueManagementService.class)
    public OrderRoutingQueueProvisioningSpi journeyQueueProvisioningAdapter(
            QueueManagementService queueService, DepartmentQueueEntryRepository queueEntries) {
        return new DepartmentQueueAdapter(queueService, queueEntries);
    }

    public static final class DepartmentQueueAdapter implements OrderRoutingQueueProvisioningSpi {
        private final QueueManagementService queueService;
        private final DepartmentQueueEntryRepository entries;

        public DepartmentQueueAdapter(QueueManagementService queueService, DepartmentQueueEntryRepository entries) {
            this.queueService = Objects.requireNonNull(queueService, "Queue service is required");
            this.entries = Objects.requireNonNull(entries, "Queue repository is required");
        }

        @Override
        public UUID createOrGetQueueEntry(
                DepartmentOrderRoutingEntity routing, boolean emergency, String actorId) {
            Objects.requireNonNull(routing, "Signed order routing is required");
            if (routing.getTargetDepartment() == OrderTargetDepartment.EXTERNAL_PHARMACY) {
                throw new IllegalArgumentException("External pharmacy must be routed through an external adapter");
            }
            String department = departmentCode(routing.getTargetDepartment());
            String idempotency = "ORDER-" + routing.getOrderId() + "-" + routing.getTargetDepartment().name();
            DepartmentQueueEntryEntity entry = queueService.enqueue(
                    routing.getFacilityId(),
                    new EnqueueRequest(
                            department,
                            routing.getPatientId(),
                            routing.getEncounterId(),
                            routing.getOrderId(),
                            null,
                            routing.getChildWorkflowId(),
                            emergency ? QueuePriority.STAT : QueuePriority.ROUTINE,
                            routing.getFinancialClearance().name(),
                            null,
                            emergency ? 15 : 90,
                            idempotency),
                    actorId);
            validateIdentity(routing, entry);
            return entry.getId();
        }

        @Override
        public void synchronizeFinancialClearance(DepartmentOrderRoutingEntity routing) {
            DepartmentQueueEntryEntity entry = requireEntry(routing);
            if (entry.getQueueStatus() == QueueStatus.COMPLETED
                    || entry.getQueueStatus() == QueueStatus.CANCELLED) {
                throw new IllegalStateException("Cannot retroactively change clearance of closed queue work");
            }
            queueService.updateFinancialClearance(
                    routing.getFacilityId(), entry.getId(), routing.getFinancialClearance().name());
        }

        @Override
        public void requireQueueCompleted(DepartmentOrderRoutingEntity routing) {
            DepartmentQueueEntryEntity entry = requireEntry(routing);
            if (entry.getQueueStatus() != QueueStatus.COMPLETED) {
                throw new IllegalStateException(
                        "Departmental order cannot finish while queue task is " + entry.getQueueStatus());
            }
        }

        private DepartmentQueueEntryEntity requireEntry(DepartmentOrderRoutingEntity routing) {
            if (routing == null || routing.getQueueEntryId() == null) {
                throw new IllegalStateException("Departmental queue entry was never allocated");
            }
            DepartmentQueueEntryEntity entry = entries.findById(routing.getQueueEntryId())
                    .orElseThrow(() -> new IllegalStateException("Linked departmental queue task not found"));
            validateIdentity(routing, entry);
            return entry;
        }

        private static void validateIdentity(
                DepartmentOrderRoutingEntity routing, DepartmentQueueEntryEntity entry) {
            if (!Objects.equals(routing.getFacilityId(), entry.getFacilityId())
                    || !Objects.equals(routing.getPatientId(), entry.getPatientId())
                    || !Objects.equals(routing.getEncounterId(), entry.getEncounterId())
                    || !Objects.equals(routing.getOrderId(), entry.getOrderId())
                    || !Objects.equals(departmentCode(routing.getTargetDepartment()), entry.getDepartmentCode())) {
                throw new SecurityException(
                        "Queue entry identity conflicts with the signed clinical order and department");
            }
        }

        private static String departmentCode(OrderTargetDepartment target) {
            return switch (Objects.requireNonNull(target, "Department required")) {
                case LABORATORY -> "LAB_COLLECTION";
                case RADIOLOGY -> "RADIOLOGY";
                case PHARMACY -> "PHARMACY";
                case PROCEDURE -> "PROCEDURES";
                case REFERRAL -> "REFERRAL_COORDINATION";
                case EXTERNAL_PHARMACY ->
                        throw new IllegalArgumentException("External pharmacies require a dedicated handoff adapter");
            };
        }
    }
}
