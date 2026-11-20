package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.hie.domain.HieEntities.IheDocumentEntity;
import com.opeshealth.healthcore.hie.service.HieInteropService;
import com.opeshealth.healthcore.hie.service.HieInteropService.IheDocumentRepository;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InteroperabilityIheStarterTest {
    @AfterEach void clearFacility() { FacilityContext.clear(); }

    @Test void persistsCanonicalMhdDocumentWithChecksumAndMetadata() throws Exception {
        UUID facilityId = UUID.randomUUID(), patientId = UUID.randomUUID();
        FacilityContext.setFacilityId(facilityId);
        IheDocumentRepository repository = mock(IheDocumentRepository.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        InteroperabilityIheStarter.IheProfileRegistryService svc =
                new InteroperabilityIheStarter.IheProfileRegistryService(
                        mock(HieInteropService.class), repository);

        IheDocumentEntity result = svc.validateAndPublishMhdDocument(
                new InteroperabilityIheStarter.IheMhdPublishSubmission(
                        facilityId, patientId, "CLINICAL-SUMMARY", "34133-9", "urn:ihe:pcc:ips:2020",
                        "text/xml", "urn:oid:1.2.3.4", "synthetic CDA document"));
        assertEquals(facilityId, result.getFacilityId());
        assertEquals("34133-9", result.getDocumentTypeLoinc());
        assertEquals("text/xml", result.getContentType());
        assertEquals("synthetic CDA document", result.getContentUrlOrBase64());
        assertEquals("urn:oid:1.2.3.4", result.getRepositoryUniqueId());
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("synthetic CDA document".getBytes(StandardCharsets.UTF_8)));
        assertEquals(digest, result.getSha256Hash());
        verify(repository).save(result);
    }
}
