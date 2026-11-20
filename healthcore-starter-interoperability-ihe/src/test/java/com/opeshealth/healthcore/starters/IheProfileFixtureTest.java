package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.hie.domain.HieEntities.IheDocumentEntity;
import com.opeshealth.healthcore.hie.service.HieInteropService;
import com.opeshealth.healthcore.hie.service.HieInteropService.IheDocumentRepository;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import com.opeshealth.healthcore.starters.InteroperabilityIheStarter.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IheProfileFixtureTest {

    @AfterEach
    void tearDown() {
        FacilityContext.clear();
    }

    @Test
    void validatesAndPublishesIheMhdDocumentAndQueriesPixmCrossReference() {
        UUID facId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);

        HieInteropService hie = mock(HieInteropService.class);
        IheDocumentRepository docRepo = mock(IheDocumentRepository.class);
        when(docRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(hie.ihePixmLookup(patientId, "urn:oid:2.16.840.1.113883.4.1"))
                .thenReturn(Map.of("patientId", patientId, "crossReferences", List.of("CM-NID-001")));

        IheProfileRegistryService service = new IheProfileRegistryService(hie, docRepo);
        IheDocumentEntity doc = service.validateAndPublishMhdDocument(new IheMhdPublishSubmission(
                facId, patientId, "34133-9", "34133-9", "urn:ihe:iti:xds-sd:pdf:2008",
                "application/xml", "1.2.826.0.1.3680043.10.942.9", "<ClinicalDocument/>"));
        assertNotNull(doc.getSha256Hash());
        assertEquals(64, doc.getSha256Hash().length());

        Map<String, Object> pix = service.queryPixmCrossReference(patientId, "urn:oid:2.16.840.1.113883.4.1");
        assertEquals(patientId, pix.get("patientId"));
    }
}
