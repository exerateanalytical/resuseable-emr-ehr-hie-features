package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.hie.domain.HieEntities.FhirSubscriptionEntity;
import com.opeshealth.healthcore.hie.service.HieInteropService;
import com.opeshealth.healthcore.hie.service.HieInteropService.FhirSubscriptionRepository;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import com.opeshealth.healthcore.starters.FhirHieStarter.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FhirHieStarterTest {

    @AfterEach
    void tearDown() {
        FacilityContext.clear();
    }

    @Test
    void delegatesCapabilityStatementIpsExportAndSubscriptionHealth() {
        UUID facId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);

        HieInteropService hie = mock(HieInteropService.class);
        FhirSubscriptionRepository subRepo = mock(FhirSubscriptionRepository.class);
        when(hie.generateCapabilityStatementJson()).thenReturn("{\"resourceType\":\"CapabilityStatement\"}");
        UUID patId = UUID.randomUUID();
        when(hie.generateIpsSummaryBundle(patId)).thenReturn("{\"resourceType\":\"Bundle\"}");

        FhirSubscriptionEntity active = new FhirSubscriptionEntity();
        active.setFacilityId(facId);
        active.setActive(true);
        active.setCriteria("DiagnosticReport?status=final");
        when(subRepo.findByFacilityId(facId)).thenReturn(List.of(active));

        FhirHieExchangeGatewayService service = new FhirHieExchangeGatewayService(hie, subRepo);
        assertTrue(service.buildFhirR4CapabilityStatementJson().contains("CapabilityStatement"));
        assertTrue(service.exportPatientEverythingBundle(patId).contains("Bundle"));

        SubscriptionChannelHealth health = service.getSubscriptionChannelHealth();
        assertEquals(1, health.activeSubscriptions());
    }
}
