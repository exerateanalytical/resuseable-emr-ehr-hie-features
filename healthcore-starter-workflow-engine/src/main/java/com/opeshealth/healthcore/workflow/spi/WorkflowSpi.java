/*
 * =============================================================================
 * Opes HealthCore SDK — Pluggable Workflow Engine Extension SPIs
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.workflow.spi;

import com.opeshealth.healthcore.workflow.domain.WorkflowEntities.WorkflowInstanceEntity;

import java.util.Map;
import java.util.UUID;

/**
 * Service Provider Interfaces (SPIs) enabling any EMR, EHR, HIE, Pharmacy, Lab,
 * or Specialty Clinic developer to attach custom clinical gates, country regulatory
 * rules, or downstream escalations cleanly without modifying core SDK code.
 */
public final class WorkflowSpi {

    private WorkflowSpi() {}

    /** Domain slices supported by the composable workflow catalog. */
    public enum WorkflowDomainCategory {
        EMR,
        EHR,
        HIE,
        PIS,
        LIS,
        SPECIALTY,
        PUBLIC_HEALTH
    }

    /** Clinical SLA priority classes adjusting escalation thresholds cleanly. */
    public enum WorkflowPriority {
        /** Immediate life-safety / emergency resuscitation (~15 min alert target). */
        STAT,
        /** Priority same-shift turnaround (~60 min target). */
        URGENT,
        /** Standard ambulatory/operational SLA defined by the template. */
        ROUTINE
    }

    /**
     * Immutable context handed to {@link WorkflowStepHookSpi} hooks during a stage change.
     */
    public record WorkflowTransitionContext(
            UUID facilityId,
            UUID instanceId,
            String workflowCode,
            UUID subjectPatientId,
            String fromStage,
            String toStage,
            String actorId,
            String actorRole,
            String notes,
            Map<String, Object> metadata
    ) {}

    /**
     * Pluggable lifecycle gate invoked in deterministic priority order before and after
     * a workflow instance commits a stage transition.
     *
     * <p>Example uses by adopting engineering teams:
     * <ul>
     *   <li>Block {@code SURGICAL_OT_FLOW -> INTRA_OP} unless WHO Safe Surgery Sign-In checklist is 100% signed.</li>
     *   <li>Block {@code PHARMACY_DISPENSE_FLOW -> DISPENSED} if controlled narcotic witness signature is absent.</li>
     *   <li>Block {@code EHR_HIE_EXCHANGE_FLOW -> MLLP_OR_FHIR_DISPATCHED} if patient consent policy is {@code OPT_OUT}.</li>
     * </ul></p>
     */
    public interface WorkflowStepHookSpi {

        /** Return true when this hook applies to the specified {@code workflowCode}. */
        default boolean supportsWorkflow(String workflowCode) {
            return true;
        }

        /** Execution precedence (lower integers execute first). */
        default int order() {
            return 100;
        }

        /**
         * Runs inside the active ACID transaction before the stage mutation commits.
         * Throw {@link IllegalStateException} or {@link SecurityException} to abort cleanly
         * (mapped automatically to RFC 7807 HTTP 409/403 or FHIR OperationOutcome).
         */
        default void beforeTransition(WorkflowTransitionContext context) {}

        /**
         * Runs inside the same ACID transaction after step-log &amp; outbox persistence.
         */
        default void afterTransition(WorkflowTransitionContext context) {}
    }

    /**
     * Notification/escalation hook invoked whenever {@link com.opeshealth.healthcore.workflow.service.WorkflowOrchestrationService}
     * detects an overdue SLA breach on an active workflow instance.
     */
    @FunctionalInterface
    public interface WorkflowSlaEscalationSpi {
        void onSlaBreached(WorkflowInstanceEntity instance);
    }
}
