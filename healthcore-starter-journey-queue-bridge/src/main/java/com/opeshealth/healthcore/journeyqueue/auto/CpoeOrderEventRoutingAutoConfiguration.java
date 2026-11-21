package com.opeshealth.healthcore.journeyqueue.auto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opeshealth.healthcore.emr.autoconfigure.EmrClinicalAutoConfiguration;
import com.opeshealth.healthcore.emr.domain.EmrEntities.ClinicalOrderEntity;
import com.opeshealth.healthcore.emr.service.EmrClinicalWorkflowService.ClinicalOrderRepository;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService.HealthCoreDomainEvent;
import com.opeshealth.healthcore.workflow.autoconfigure.WorkflowEngineAutoConfiguration;
import com.opeshealth.healthcore.workflow.journey.ClinicalJourneyContracts.FacilityBillingPolicyConfig;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyEntities.OrderTargetDepartment;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyOrchestratorService;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyOrchestratorService.RouteSignedOrderCommand;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * Opt-in and fail-closed bridge from the *persisted* EMR CPOE outbox into
 * departmental order routing. No financial or clinical attestations are fabricated:
 * the host application MUST configure a real clinical signature/payer policy SPI.
 */
@AutoConfiguration(after = {EmrClinicalAutoConfiguration.class, WorkflowEngineAutoConfiguration.class})
public class CpoeOrderEventRoutingAutoConfiguration {

    /**
     * Institution-owned authorization boundary. Verify the stored order's signed
     * clinical authorization, clinician privileges, and financial eligibility
     * against authoritative records; NEVER trust the event body's claimed actor.
     */
    public interface ClinicalOrderRoutingAuthorizationSpi {
        VerifiedRoutingAuthorization authorize(ClinicalOrderEntity persistedOrder);
    }

    /**
     * Evidence references are immutable identifiers to clinical signing and
     * billing-policy decisions, not arbitrary boolean assertions from a patient.
     */
    public record VerifiedRoutingAuthorization(
            String clinicalSignatureEvidenceRef,
            String financialPolicyEvidenceRef,
            FacilityBillingPolicyConfig facilityPolicy,
            boolean emergencyAuthorized,
            boolean insuranceAuthorized,
            boolean creditApproved,
            boolean sponsored,
            boolean waived,
            long billedAmountMinor,
            long paidAmountMinor,
            String externalPharmacyCode
    ) {}

    @Bean
    @ConditionalOnBean(ClinicalOrderRoutingAuthorizationSpi.class)
    @ConditionalOnMissingBean(CpoeOrderEventListener.class)
    public CpoeOrderEventListener healthCoreCpoeOrderEventListener(
            ClinicalOrderRepository orders,
            PatientJourneyOrchestratorService journey,
            ClinicalOrderRoutingAuthorizationSpi authorization) {
        return new CpoeOrderEventListener(orders, journey, authorization);
    }

    public static final class CpoeOrderEventListener {
        private final ClinicalOrderRepository orders;
        private final PatientJourneyOrchestratorService journey;
        private final ClinicalOrderRoutingAuthorizationSpi authorization;
        private final ObjectMapper mapper = new ObjectMapper();

        public CpoeOrderEventListener(
                ClinicalOrderRepository orders,
                PatientJourneyOrchestratorService journey,
                ClinicalOrderRoutingAuthorizationSpi authorization) {
            this.orders = Objects.requireNonNull(orders);
            this.journey = Objects.requireNonNull(journey);
            this.authorization = Objects.requireNonNull(authorization);
        }

        /**
         * Invoked synchronously by the Spring in-process outbox relay after the
         * original clinical order transaction has committed. The route and queue
         * operations share this transaction. Retries reuse the same order identity.
         *
         * Kafka/RabbitMQ installations require a compatible durable consumer with
         * the same validation and idempotency contract.
         */
        @EventListener
        @Transactional
        public void onClinicalOrderAccepted(HealthCoreDomainEvent event) {
            if (event == null || !"ClinicalOrder".equals(event.aggregateType())
                    || (!"MedicationOrderedEvent".equals(event.eventType())
                        && !"DiagnosticOrderedEvent".equals(event.eventType()))) {
                return;
            }
            if (event.eventId() == null || event.aggregateId() == null || event.facilityId() == null) {
                throw new SecurityException("Clinical order event lacks verified outbox identity");
            }
            ClinicalOrderEntity order = orders.findById(event.aggregateId())
                    .orElseThrow(() -> new NoSuchElementException(
                            "Persisted clinical order not found: " + event.aggregateId()));
            if (!event.facilityId().equals(order.getFacilityId())
                    || !"ACTIVE".equalsIgnoreCase(order.getStatus())
                    || order.getPatientId() == null || order.getEncounterId() == null
                    || order.getOrderedBy() == null || order.getOrderedBy().isBlank()) {
                throw new SecurityException("Event does not match an active tenant-bound clinical order");
            }

            validateEventProvenance(event, order);
            OrderTargetDepartment target = departmentForOrder(order.getOrderCategory());
            if ("MedicationOrderedEvent".equals(event.eventType())
                    != (target == OrderTargetDepartment.PHARMACY
                        || target == OrderTargetDepartment.EXTERNAL_PHARMACY)) {
                throw new SecurityException("Outbox event type conflicts with stored order category");
            }
            VerifiedRoutingAuthorization approval = authorization.authorize(order);
            if (approval == null || isBlank(approval.clinicalSignatureEvidenceRef())
                    || isBlank(approval.financialPolicyEvidenceRef())
                    || approval.facilityPolicy() == null
                    || approval.billedAmountMinor() < 0 || approval.paidAmountMinor() < 0) {
                throw new SecurityException(
                        "No verified clinician signature, billing decision or valid financial context");
            }
            if (target == OrderTargetDepartment.PHARMACY
                    && !isBlank(approval.externalPharmacyCode())) {
                target = OrderTargetDepartment.EXTERNAL_PHARMACY;
            }
            // Every Boolean in this command must come from the trusted verifier,
            // not the event payload or a caller-supplied 'priority' string.
            journey.routeSignedOrder(
                    new RouteSignedOrderCommand(
                            order.getFacilityId(), order.getPatientId(), order.getEncounterId(),
                            null, order.getId(), target, approval.externalPharmacyCode(),
                            approval.emergencyAuthorized(), approval.insuranceAuthorized(),
                            approval.creditApproved(), approval.sponsored(), approval.waived(),
                            approval.billedAmountMinor(), approval.paidAmountMinor(),
                            order.getOrderedBy(), "CPOE-ROUTE-" + order.getId()),
                    approval.facilityPolicy());
        }

        private void validateEventProvenance(HealthCoreDomainEvent event, ClinicalOrderEntity order) {
            try {
                JsonNode json = mapper.readTree(event.payloadJson());
                if (json == null
                        || !order.getId().toString().equals(text(json, "orderId"))
                        || !order.getFacilityId().toString().equals(text(json, "facilityId"))
                        || !order.getPatientId().toString().equals(text(json, "patientId"))
                        || !order.getEncounterId().toString().equals(text(json, "encounterId"))
                        || !order.getOrderCategory().equalsIgnoreCase(text(json, "orderCategory"))
                        || !order.getOrderedBy().equals(text(json, "orderedBy"))) {
                    throw new SecurityException(
                            "Clinical order event provenance differs from persisted order");
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                throw new SecurityException("Malformed clinical order event", invalid);
            }
        }

        private static String text(JsonNode node, String field) {
            JsonNode value = node.get(field);
            return value != null && value.isTextual() ? value.asText() : null;
        }

        private static OrderTargetDepartment departmentForOrder(String category) {
            if (category == null) {
                throw new IllegalArgumentException("Clinical order category is mandatory");
            }
            return switch (category.trim().toUpperCase(Locale.ROOT)) {
                case "MEDICATION" -> OrderTargetDepartment.PHARMACY;
                case "LAB", "LABORATORY" -> OrderTargetDepartment.LABORATORY;
                case "IMAGING", "RADIOLOGY" -> OrderTargetDepartment.RADIOLOGY;
                case "PROCEDURE" -> OrderTargetDepartment.PROCEDURE;
                case "REFERRAL" -> OrderTargetDepartment.REFERRAL;
                default -> throw new IllegalArgumentException(
                        "No authorized departmental route for order category: " + category);
            };
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }
}
