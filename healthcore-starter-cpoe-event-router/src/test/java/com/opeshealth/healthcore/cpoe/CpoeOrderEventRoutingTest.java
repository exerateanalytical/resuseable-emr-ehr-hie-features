package com.opeshealth.healthcore.cpoe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opeshealth.healthcore.cpoe.autoconfigure.CpoeOrderEventRoutingAutoConfiguration.*;
import com.opeshealth.healthcore.emr.domain.EmrEntities.ClinicalOrderEntity;
import com.opeshealth.healthcore.emr.service.EmrClinicalWorkflowService.ClinicalOrderRepository;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService.HealthCoreDomainEvent;
import com.opeshealth.healthcore.workflow.journey.ClinicalJourneyContracts.FacilityBillingPolicyConfig;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyEntities.OrderTargetDepartment;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyOrchestratorService;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyOrchestratorService.RouteSignedOrderCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit boundary of the authenticated outbox-to-routing adapter.
 * Does NOT replace PostgreSQL + real Spring event relay + signed clinical evidence IT.
 */
class CpoeOrderEventRoutingTest {
    private ClinicalOrderRepository orders;
    private PatientJourneyOrchestratorService journey;
    private ClinicalOrderRoutingAuthorizationSpi authorization;
    private CpoeOrderEventListener listener;
    private ClinicalOrderEntity stored;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setup() {
        orders = mock(ClinicalOrderRepository.class);
        journey = mock(PatientJourneyOrchestratorService.class);
        authorization = mock(ClinicalOrderRoutingAuthorizationSpi.class);
        listener = new CpoeOrderEventListener(orders, journey, authorization);
        stored = new ClinicalOrderEntity();
        stored.setFacilityId(UUID.randomUUID());
        stored.setPatientId(UUID.randomUUID());
        stored.setEncounterId(UUID.randomUUID());
        stored.setOrderedBy("doctor-licensed");
        stored.setOrderCategory("LAB");
        stored.setStatus("ACTIVE");
        when(orders.findById(stored.getId())).thenReturn(Optional.of(stored));
    }

    @Test
    void verifiedLabOrderCreatesOneAuthorizedDepartmentRoutingCommand() throws Exception {
        when(authorization.authorize(stored)).thenReturn(approved(15000, 0, false));
        HealthCoreDomainEvent accepted = event(stored, "DiagnosticOrderedEvent", validBody(stored));
        listener.onClinicalOrderAccepted(accepted);
        ArgumentCaptor<RouteSignedOrderCommand> cmd =
                ArgumentCaptor.forClass(RouteSignedOrderCommand.class);
        verify(journey).routeSignedOrder(cmd.capture(), eq(FacilityBillingPolicyConfig.defaultFacilityPolicy()));
        RouteSignedOrderCommand actual = cmd.getValue();
        assertEquals(stored.getFacilityId(), actual.facilityId());
        assertEquals(stored.getPatientId(), actual.patientId());
        assertEquals(stored.getEncounterId(), actual.encounterId());
        assertEquals(stored.getId(), actual.orderId());
        assertEquals(OrderTargetDepartment.LABORATORY, actual.targetDepartment());
        assertEquals(15000, actual.billedAmountMinor());
        assertEquals(0, actual.paidAmountMinor());
        assertFalse(actual.emergencyUrgent());
        assertEquals("CPOE-ROUTE-" + stored.getId(), actual.idempotencyKey());
        // The journey engine is responsible for deduplication across redeliveries.
        listener.onClinicalOrderAccepted(accepted);
        verify(journey, times(2)).routeSignedOrder(
                argThat(c -> c.idempotencyKey().equals("CPOE-ROUTE-" + stored.getId())),
                eq(FacilityBillingPolicyConfig.defaultFacilityPolicy()));
    }

    @Test
    void medicationUsesInHousePharmacyAndVerifiedExternalHandoffOnly() throws Exception {
        stored.setOrderCategory("MEDICATION");
        when(authorization.authorize(stored)).thenReturn(approved(20000, 0, false));
        listener.onClinicalOrderAccepted(event(stored, "MedicationOrderedEvent", validBody(stored)));
        verify(journey).routeSignedOrder(
                argThat(c -> c.targetDepartment() == OrderTargetDepartment.PHARMACY),
                any(FacilityBillingPolicyConfig.class));
        clearInvocations(journey);
        when(authorization.authorize(stored)).thenReturn(new VerifiedRoutingAuthorization(
                "SIGNED:abc", "BILLING:confirmed", FacilityBillingPolicyConfig.defaultFacilityPolicy(),
                false, false, false, false, false, 20000, 0, "PARTNER-01"));
        listener.onClinicalOrderAccepted(event(stored, "MedicationOrderedEvent", validBody(stored)));
        verify(journey).routeSignedOrder(
                argThat(c -> c.targetDepartment() == OrderTargetDepartment.EXTERNAL_PHARMACY
                        && "PARTNER-01".equals(c.externalPharmacyCode())),
                any(FacilityBillingPolicyConfig.class));
    }

    @Test
    void tamperedTenantPatientOrEventDomainCannotRoute() throws Exception {
        when(authorization.authorize(stored)).thenReturn(approved(10000, 0, false));
        assertThrows(SecurityException.class, () -> listener.onClinicalOrderAccepted(
                new HealthCoreDomainEvent(UUID.randomUUID(), UUID.randomUUID(),
                        "ClinicalOrder", stored.getId(), "DiagnosticOrderedEvent",
                        validBody(stored), null)));

        String poisonedBody = json.writeValueAsString(Map.of(
                "orderId", stored.getId().toString(),
                "facilityId", stored.getFacilityId().toString(),
                "patientId", UUID.randomUUID().toString(),
                "encounterId", stored.getEncounterId().toString(),
                "orderCategory", "LAB", "orderedBy", stored.getOrderedBy()));
        assertThrows(SecurityException.class, () -> listener.onClinicalOrderAccepted(
                event(stored, "DiagnosticOrderedEvent", poisonedBody)));
        assertThrows(SecurityException.class, () -> listener.onClinicalOrderAccepted(
                event(stored, "MedicationOrderedEvent", validBody(stored))));
        verifyNoInteractions(journey);
    }

    @Test
    void unsignedOrUnverifiedBillingEvidenceFailsClosed() throws Exception {
        HealthCoreDomainEvent accepted = event(stored, "DiagnosticOrderedEvent", validBody(stored));
        assertThrows(SecurityException.class, () -> listener.onClinicalOrderAccepted(accepted));
        when(authorization.authorize(stored)).thenReturn(
                new VerifiedRoutingAuthorization(
                        null, "BILLING:confirmed", FacilityBillingPolicyConfig.defaultFacilityPolicy(),
                        false, false, false, false, false, 15000, 0, null));
        assertThrows(SecurityException.class, () -> listener.onClinicalOrderAccepted(accepted));
        when(authorization.authorize(stored)).thenReturn(
                new VerifiedRoutingAuthorization(
                        "SIGNED:abc", null, FacilityBillingPolicyConfig.defaultFacilityPolicy(),
                        false, false, false, false, false, 15000, 0, null));
        assertThrows(SecurityException.class, () -> listener.onClinicalOrderAccepted(accepted));
        stored.setStatus("CANCELLED");
        assertThrows(SecurityException.class, () -> listener.onClinicalOrderAccepted(accepted));
        verifyNoInteractions(journey);
    }

    private VerifiedRoutingAuthorization approved(long billed, long paid, boolean emergency) {
        return new VerifiedRoutingAuthorization(
                "SIGNED:abc", "BILLING:confirmed", FacilityBillingPolicyConfig.defaultFacilityPolicy(),
                emergency, false, false, false, false, billed, paid, null);
    }

    private HealthCoreDomainEvent event(ClinicalOrderEntity order, String eventType, String payload) {
        return new HealthCoreDomainEvent(
                UUID.randomUUID(), order.getFacilityId(), "ClinicalOrder", order.getId(),
                eventType, payload, null);
    }

    private String validBody(ClinicalOrderEntity order) throws Exception {
        return json.writeValueAsString(Map.of(
                "orderId", order.getId().toString(),
                "facilityId", order.getFacilityId().toString(),
                "patientId", order.getPatientId().toString(),
                "encounterId", order.getEncounterId().toString(),
                "orderCategory", order.getOrderCategory(),
                "orderedBy", order.getOrderedBy()));
    }
}
