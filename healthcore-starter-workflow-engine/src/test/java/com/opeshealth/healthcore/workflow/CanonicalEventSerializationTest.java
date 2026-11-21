package com.opeshealth.healthcore.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService.OutboxRepository;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.workflow.journey.ClinicalJourneyContracts.DefaultFacilityBillingClearancePolicy;
import com.opeshealth.healthcore.workflow.journey.ClinicalJourneyContracts.FacilityBillingPolicyConfig;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyEntities.DepartmentOrderRoutingEntity;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyEntities.OrderTargetDepartment;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyOrchestratorService;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyOrchestratorService.DepartmentOrderRoutingRepository;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyOrchestratorService.RouteSignedOrderCommand;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Ensures every accepted clinical route can serialize its ISO-8601 event time. */
class CanonicalEventSerializationTest {

    @Test
    void clinicalRoutePersistsReplaySafeCanonicalJsonWithIsoInstant() throws Exception {
        DepartmentOrderRoutingRepository routing = mock(DepartmentOrderRoutingRepository.class);
        OutboxRepository outbox = mock(OutboxRepository.class);
        when(routing.save(any(DepartmentOrderRoutingEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(outbox.save(any(TransactionalOutboxEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PatientJourneyOrchestratorService journey = new PatientJourneyOrchestratorService(
                null, null, routing, null, null, null, outbox,
                new DefaultFacilityBillingClearancePolicy(), List.of());

        UUID facility = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        UUID encounter = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        journey.routeSignedOrder(new RouteSignedOrderCommand(
                facility, patient, encounter, null, order, OrderTargetDepartment.LABORATORY,
                null, false, true, false, false, false, 15000, 0,
                "doctor-licensed", "ISO-INSTANT-" + order),
                FacilityBillingPolicyConfig.defaultFacilityPolicy());

        ArgumentCaptor<TransactionalOutboxEntity> capture =
                ArgumentCaptor.forClass(TransactionalOutboxEntity.class);
        verify(outbox).save(capture.capture());
        TransactionalOutboxEntity event = capture.getValue();
        assertEquals(facility, event.getFacilityId());
        assertEquals("LaboratoryOrderAccepted", event.getEventType());
        assertTrue(event.getIdempotencyKey().startsWith("EVT-ISO-INSTANT-"));

        JsonNode body = new ObjectMapper().readTree(event.getPayloadJson());
        assertEquals("LaboratoryOrderAccepted", body.path("eventType").asText());
        assertEquals(facility.toString(), body.path("facilityId").asText());
        String occurredAt = body.path("occurredAt").asText();
        assertFalse(occurredAt.isBlank());
        assertDoesNotThrow(() -> Instant.parse(occurredAt),
                "Clinical event timestamps must survive Jackson serialization in ISO-8601");
    }
}
