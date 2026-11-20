package com.opeshealth.healthcore.integrationops;

import com.opeshealth.healthcore.integrationops.domain.IntegrationOpsEntities.IntegrationMessageLedgerEntity;
import com.opeshealth.healthcore.integrationops.domain.IntegrationOpsEntities.IntegrationReplayAuditEntity;
import com.opeshealth.healthcore.integrationops.domain.IntegrationOpsEntities.PartnerConformanceProfileEntity;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService.*;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IntegrationOpsServiceTest {

    private PartnerProfileRepository partnerRepo;
    private MessageLedgerRepository messageRepo;
    private ReplayAuditRepository replayRepo;
    private ClinicalAuditService auditService;
    private IntegrationOpsService service;
    private UUID facilityId;

    @BeforeEach
    void setUp() {
        partnerRepo = mock(PartnerProfileRepository.class);
        messageRepo = mock(MessageLedgerRepository.class);
        replayRepo = mock(ReplayAuditRepository.class);
        auditService = mock(ClinicalAuditService.class);
        when(partnerRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(messageRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(replayRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new IntegrationOpsService(partnerRepo, messageRepo, replayRepo, auditService);
        facilityId = UUID.randomUUID();
        FacilityContext.setFacilityId(facilityId);
    }

    @AfterEach
    void tearDown() {
        FacilityContext.clear();
    }

    @Test
    void enforcesTlsEndpointAndConformanceProfile() {
        assertThrows(IllegalArgumentException.class, () -> service.upsertPartnerProfile(
                new UpsertPartnerRequest("LAB-EXT", "External Lab", "FHIR_R4",
                        "http://insecure.example/fhir", "MTLS", "Bundle,DiagnosticReport", 60, true),
                "ops.eng"));

        PartnerConformanceProfileEntity partner = service.upsertPartnerProfile(
                new UpsertPartnerRequest("LAB-EXT", "External Lab", "FHIR_R4",
                        "https://hie.example.org/fhir", "MTLS", "Bundle,DiagnosticReport", 60, true),
                "ops.eng");
        assertEquals("LAB-EXT", partner.getPartnerCode());
        assertEquals("CLOSED", partner.getCircuitState());

        when(partnerRepo.findByIdAndFacilityId(partner.getId(), facilityId)).thenReturn(Optional.of(partner));
        assertThrows(IllegalArgumentException.class, () -> service.enqueueOutbound(
                new EnqueueMessageRequest(partner.getId(), "idem-bad", "Invoice", "{}", 3), "ops.eng"));
    }

    @Test
    void enforcesIdempotencyExponentialRetryDlqAndAuditedReplay() {
        PartnerConformanceProfileEntity partner = new PartnerConformanceProfileEntity();
        partner.setFacilityId(facilityId);
        partner.setPartnerCode("NAT-HIE");
        partner.setPartnerName("National HIE");
        partner.setProtocolFamily("HL7_V2");
        partner.setEndpointUrl("mllp+tls://hie.gov.cm:2575");
        partner.setAllowedMessageTypes("ADT^A01,ORM^O01,ORU^R01");
        when(partnerRepo.findByIdAndFacilityId(partner.getId(), facilityId)).thenReturn(Optional.of(partner));

        IntegrationMessageLedgerEntity msg = service.enqueueOutbound(
                new EnqueueMessageRequest(partner.getId(), "msg-key-1", "ORM^O01", "MSH|^~\\&|...", 2),
                "ops.eng");
        assertEquals("QUEUED", msg.getDeliveryStatus());

        when(messageRepo.findByFacilityIdAndDirectionAndIdempotencyKey(facilityId, "OUTBOUND", "msg-key-1"))
                .thenReturn(Optional.of(msg));
        IntegrationMessageLedgerEntity duplicate = service.enqueueOutbound(
                new EnqueueMessageRequest(partner.getId(), "msg-key-1", "ORM^O01", "MSH|^~\\&|...", 2),
                "ops.eng");
        assertSame(msg, duplicate);

        when(messageRepo.findByIdAndFacilityId(msg.getId(), facilityId)).thenReturn(Optional.of(msg));
        IntegrationMessageLedgerEntity retry = service.recordDeliveryFailure(
                msg.getId(), new RecordFailureRequest(503, "UPSTREAM_503", "Gateway timeout"), "relay");
        assertEquals("RETRYING", retry.getDeliveryStatus());
        assertNotNull(retry.getNextRetryAt());

        IntegrationMessageLedgerEntity dlq = service.recordDeliveryFailure(
                msg.getId(), new RecordFailureRequest(503, "UPSTREAM_503", "Gateway timeout"), "relay");
        assertEquals("DEAD_LETTERED", dlq.getDeliveryStatus());
        assertNull(dlq.getNextRetryAt());

        IntegrationMessageLedgerEntity replayed = service.replayMessage(
                msg.getId(), new ReplayRequest("ops.lead", "Partner restored after maintenance window"));
        assertEquals("REPLAYED", replayed.getDeliveryStatus());
        assertEquals(0, replayed.getAttemptCount());
        verify(replayRepo, times(1)).save(any(IntegrationReplayAuditEntity.class));
    }

    @Test
    void tripsCircuitBreakerAfterFiveConsecutiveFailuresAndBlocksDispatch() {
        PartnerConformanceProfileEntity partner = new PartnerConformanceProfileEntity();
        partner.setFacilityId(facilityId);
        partner.setPartnerCode("PACS-HUB");
        partner.setPartnerName("Regional PACS");
        partner.setProtocolFamily("DICOMWEB");
        partner.setEndpointUrl("https://pacs.example.org/dicom-web");
        partner.setAllowedMessageTypes("STOW-RS");
        partner.setConsecutiveFailures(4);
        when(partnerRepo.findByIdAndFacilityId(partner.getId(), facilityId)).thenReturn(Optional.of(partner));

        IntegrationMessageLedgerEntity msg = new IntegrationMessageLedgerEntity();
        msg.setFacilityId(facilityId);
        msg.setPartnerId(partner.getId());
        msg.setDirection("OUTBOUND");
        msg.setIdempotencyKey("pacs-5");
        msg.setMaxAttempts(5);
        when(messageRepo.findByIdAndFacilityId(msg.getId(), facilityId)).thenReturn(Optional.of(msg));

        service.recordDeliveryFailure(msg.getId(), new RecordFailureRequest(502, "BAD_GATEWAY", "Down"), "relay");
        assertEquals("OPEN", partner.getCircuitState());

        assertThrows(ResponseStatusException.class, () -> service.enqueueOutbound(
                new EnqueueMessageRequest(partner.getId(), "pacs-6", "STOW-RS", "{}", 3), "ops.eng"));
    }
}
