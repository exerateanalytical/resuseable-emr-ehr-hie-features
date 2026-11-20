/*
 * =============================================================================
 * Opes HealthCore SDK — Canonical Clinical Journey, Billing Clearance & Event Contracts
 * =============================================================================
 */
package com.opeshealth.healthcore.workflow.journey;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;

import java.time.Instant;
import java.util.*;

/**
 * Reusable, zero-coupling contracts for the OPES HealthCore Master Clinical Workflow
 * &amp; Patient Journey architecture:
 * <ul>
 *   <li>Canonical identifier separation ({@code patientId}, {@code encounterId}, {@code workflowInstanceId},
 *       {@code orderId}, {@code taskId}, {@code queueEntryId}, {@code resultId}, {@code invoiceId},
 *       {@code notificationId})</li>
 *   <li>Configurable billing &amp; eligibility clearance SPI ({@link WorkflowBillingClearancePolicySpi})</li>
 *   <li>28 canonical replay-safe domain event types ({@link CanonicalWorkflowEventType})</li>
 *   <li>Shared 25-attribute reusable workflow checklist definition ({@link WorkflowChecklistDefinition})</li>
 * </ul>
 */
public final class ClinicalJourneyContracts {

    private ClinicalJourneyContracts() {}

    /** Section 2 — Strict separation of patient, encounter, workflow, order, task, queue, result, invoice, and notification identifiers. */
    public record ClinicalJourneyIdentifiers(
            UUID patientId,
            UUID encounterId,
            UUID workflowInstanceId,
            UUID orderId,
            UUID taskId,
            UUID queueEntryId,
            UUID resultId,
            UUID invoiceId,
            UUID notificationId
    ) {}

    /** Section 5 — All 11 supported workflow financial &amp; eligibility clearance outcomes. */
    public enum PaymentClearanceOutcome {
        PAYMENT_NOT_REQUIRED,
        PAYMENT_PENDING,
        PARTIALLY_PAID,
        PAID,
        INSURANCE_AUTHORIZED,
        CREDIT_APPROVED,
        SPONSORED,
        DEFERRED,
        WAIVED,
        EMERGENCY_OVERRIDE,
        REJECTED_OR_EXPIRED;

        public boolean permitsFulfillmentActivation() {
            return switch (this) {
                case PAYMENT_NOT_REQUIRED, PAID, INSURANCE_AUTHORIZED, CREDIT_APPROVED,
                     SPONSORED, DEFERRED, WAIVED, EMERGENCY_OVERRIDE -> true;
                case PAYMENT_PENDING, PARTIALLY_PAID, REJECTED_OR_EXPIRED -> false;
            };
        }
    }

    public enum ServiceClearanceCategory {
        CONSULTATION,
        LABORATORY,
        IMAGING,
        PHARMACY,
        PROCEDURE,
        EMERGENCY_STABILIZATION,
        CRITICAL_RESULT_COMMUNICATION
    }

    public record BillingClearanceEvaluationRequest(
            UUID facilityId,
            UUID patientId,
            UUID encounterId,
            UUID orderId,
            ServiceClearanceCategory serviceCategory,
            boolean emergencyUrgent,
            boolean criticalFindingCommunication,
            boolean insuranceAuthorized,
            boolean creditApproved,
            boolean sponsored,
            boolean waived,
            long billedAmountMinor,
            long paidAmountMinor
    ) {}

    public record BillingClearanceDecision(
            PaymentClearanceOutcome outcome,
            boolean fulfillmentEligible,
            boolean routeToCashierWorklist,
            boolean preserveUnderlyingClinicalOrder,
            String policyReason
    ) {}

    public record FacilityBillingPolicyConfig(
            boolean consultationPrepayRequired,
            boolean laboratoryPrepayRequired,
            boolean imagingPrepayRequired,
            boolean pharmacyPrepayRequired,
            boolean allowPartialPaymentActivation,
            boolean consolidatedBillingEnabled
    ) {
        public static FacilityBillingPolicyConfig defaultFacilityPolicy() {
            return new FacilityBillingPolicyConfig(true, true, true, true, false, false);
        }
    }

    /**
     * Section 5 — Reusable policy SPI for workflow payment &amp; eligibility clearance.
     * Reuses existing billing/claims modules without creating a second financial ledger.
     */
    public interface WorkflowBillingClearancePolicySpi {
        BillingClearanceDecision evaluate(BillingClearanceEvaluationRequest request, FacilityBillingPolicyConfig facilityConfig);
    }

    /**
     * Default facility-configurable billing clearance evaluator guaranteeing:
     * <ol>
     *   <li>Emergency stabilization, urgent escalation, and critical-finding communication are NEVER blocked by routine payment rules.</li>
     *   <li>Financial holds NEVER cancel or erase the underlying clinical order ({@code preserveUnderlyingClinicalOrder = true}).</li>
     * </ol>
     */
    public static final class DefaultFacilityBillingClearancePolicy implements WorkflowBillingClearancePolicySpi {
        @Override
        public BillingClearanceDecision evaluate(BillingClearanceEvaluationRequest req, FacilityBillingPolicyConfig cfg) {
            Objects.requireNonNull(req, "BillingClearanceEvaluationRequest is required");
            FacilityBillingPolicyConfig eff = cfg != null ? cfg : FacilityBillingPolicyConfig.defaultFacilityPolicy();

            // Safety invariant: emergency stabilization and critical-finding escalation are never blocked by payment
            if (req.emergencyUrgent()
                    || req.criticalFindingCommunication()
                    || req.serviceCategory() == ServiceClearanceCategory.EMERGENCY_STABILIZATION
                    || req.serviceCategory() == ServiceClearanceCategory.CRITICAL_RESULT_COMMUNICATION) {
                return new BillingClearanceDecision(
                        PaymentClearanceOutcome.EMERGENCY_OVERRIDE, true, false, true,
                        "Emergency stabilization / critical clinical escalation bypasses routine prepayment rules");
            }
            if (req.waived()) {
                return new BillingClearanceDecision(PaymentClearanceOutcome.WAIVED, true, false, true, "Fee waived by authorized policy");
            }
            if (req.sponsored()) {
                return new BillingClearanceDecision(PaymentClearanceOutcome.SPONSORED, true, false, true, "Covered by external sponsor/program");
            }
            if (req.insuranceAuthorized()) {
                return new BillingClearanceDecision(PaymentClearanceOutcome.INSURANCE_AUTHORIZED, true, false, true, "Payer prior-authorization verified");
            }
            if (req.creditApproved()) {
                return new BillingClearanceDecision(PaymentClearanceOutcome.CREDIT_APPROVED, true, false, true, "Corporate or facility credit approved");
            }

            boolean prepayRequired = switch (req.serviceCategory()) {
                case CONSULTATION -> eff.consultationPrepayRequired();
                case LABORATORY -> eff.laboratoryPrepayRequired();
                case IMAGING -> eff.imagingPrepayRequired();
                case PHARMACY -> eff.pharmacyPrepayRequired();
                case PROCEDURE -> eff.laboratoryPrepayRequired();
                default -> false;
            };
            if (!prepayRequired || req.billedAmountMinor() <= 0L) {
                return new BillingClearanceDecision(
                        PaymentClearanceOutcome.PAYMENT_NOT_REQUIRED, true, false, true,
                        "Facility policy does not require prepayment for " + req.serviceCategory());
            }
            if (req.paidAmountMinor() >= req.billedAmountMinor()) {
                return new BillingClearanceDecision(PaymentClearanceOutcome.PAID, true, false, true, "Full payment confirmed");
            }
            if (req.paidAmountMinor() > 0L) {
                boolean eligible = eff.allowPartialPaymentActivation();
                return new BillingClearanceDecision(
                        PaymentClearanceOutcome.PARTIALLY_PAID, eligible, !eligible, true,
                        eligible ? "Partial deposit accepted per facility policy" : "Partial payment recorded; remaining balance required");
            }
            return new BillingClearanceDecision(
                    PaymentClearanceOutcome.PAYMENT_PENDING, false, true, true,
                    "Awaiting payment or coverage clearance; clinical order preserved intact");
        }
    }

    /** Section 10 — All 28 canonical durable clinical workflow domain events. */
    public enum CanonicalWorkflowEventType {
        PatientMatched,
        PatientRegistered,
        EncounterStarted,
        PatientArrived,
        TriageCompleted,
        ClinicalPriorityChanged,
        VitalSignsRecorded,
        QueueEntryCreated,
        QueueEntryCalled,
        ConsultationStarted,
        ConsultationCompleted,
        ClinicalOrderSigned,
        FinancialClearanceRequired,
        ServiceCleared,
        LaboratoryOrderAccepted,
        ImagingOrderAccepted,
        PrescriptionRouted,
        SpecimenCollected,
        LaboratoryResultVerified,
        RadiologyReportSigned,
        MedicationDispensed,
        CriticalResultDetected,
        ClinicianReviewRequired,
        ClinicianResultAcknowledged,
        PatientNotificationRequested,
        PatientNotificationDelivered,
        FollowUpRequired,
        EncounterDischarged
    }

    /** Section 10 — Validated durable domain event envelope for transactional outbox/inbox replay safety. */
    public record CanonicalDomainEventEnvelope(
            UUID eventId,
            CanonicalWorkflowEventType eventType,
            String eventVersion,
            UUID facilityId,
            UUID patientId,
            UUID encounterId,
            UUID orderId,
            UUID correlationId,
            UUID causationId,
            Instant occurredAt,
            String originatingService,
            String actorId,
            String idempotencyKey,
            Map<String, Object> payload
    ) {
        public static CanonicalDomainEventEnvelope create(
                CanonicalWorkflowEventType eventType,
                UUID facilityId,
                UUID patientId,
                UUID encounterId,
                UUID orderId,
                UUID correlationId,
                UUID causationId,
                String originatingService,
                String actorId,
                String idempotencyKey,
                Map<String, Object> payload) {
            CanonicalDomainEventEnvelope env = new CanonicalDomainEventEnvelope(
                    UuidV7Generator.nextUuid(),
                    eventType,
                    "1.0",
                    facilityId,
                    patientId,
                    encounterId,
                    orderId,
                    correlationId != null ? correlationId : UuidV7Generator.nextUuid(),
                    causationId,
                    Instant.now(),
                    originatingService,
                    actorId,
                    idempotencyKey,
                    payload != null ? Map.copyOf(payload) : Map.of()
            );
            env.validate();
            return env;
        }

        public void validate() {
            if (eventId == null) throw new IllegalArgumentException("eventId is required");
            if (eventType == null) throw new IllegalArgumentException("eventType is required");
            if (eventVersion == null || eventVersion.isBlank()) throw new IllegalArgumentException("eventVersion is required");
            if (facilityId == null) throw new IllegalArgumentException("facilityId is required");
            if (correlationId == null) throw new IllegalArgumentException("correlationId is required");
            if (occurredAt == null) throw new IllegalArgumentException("occurredAt is required");
            if (originatingService == null || originatingService.isBlank()) throw new IllegalArgumentException("originatingService is required");
            if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey is required");
        }
    }

    /** Section 9 — Shared 25-attribute reusable workflow checklist definition contract. */
    public record WorkflowChecklistDefinition(
            String workflowId,
            String version,
            String applicableDomain,
            String careSetting,
            String descriptionAndPurpose,
            List<String> requiredModules,
            List<String> optionalModules,
            String startingEvent,
            Map<String, List<String>> parentChildRelations,
            List<String> requiredInputData,
            String responsibleRoleOrTeam,
            Map<String, List<String>> allowedStatesAndTransitions,
            List<String> prerequisiteConditions,
            List<String> safetyChecks,
            List<String> financialAndAuthorizationPolicies,
            List<String> tasksToCreate,
            List<String> queuesToUpdate,
            List<String> eventsToPublish,
            List<String> notificationsToGenerate,
            List<String> outputsAndClinicalRecords,
            List<String> exceptionRoutes,
            String cancellationAndResumptionRules,
            Map<String, Integer> slaDeadlinesAndEscalationMinutes,
            String auditAndProvenanceRequirements,
            String completionCriteria,
            List<String> apiExamples,
            String testsAndClinicalReviewStatus
    ) {}
}
