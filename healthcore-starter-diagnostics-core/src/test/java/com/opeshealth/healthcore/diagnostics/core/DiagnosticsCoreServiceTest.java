package com.opeshealth.healthcore.diagnostics.core;

import com.opeshealth.healthcore.diagnostics.core.domain.DiagnosticsCoreEntities.*;
import com.opeshealth.healthcore.diagnostics.core.service.DiagnosticsCoreService;
import com.opeshealth.healthcore.diagnostics.core.service.DiagnosticsCoreService.*;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DiagnosticsCoreServiceTest {

    @AfterEach
    void clearTenant() {
        FacilityContext.clear();
    }

    @Test
    void enforcesSpecimenRejectionGateTatSlaDetectionAndIso15189IncidentCapture() {
        UUID facId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);

        ReferringPartnerRepository partnerRepo = mock(ReferringPartnerRepository.class);
        DiagnosticServiceOrderRepository orderRepo = mock(DiagnosticServiceOrderRepository.class);
        QualityIncidentRepository incidentRepo = mock(QualityIncidentRepository.class);
        when(partnerRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(orderRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(incidentRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        DiagnosticsCoreService service = new DiagnosticsCoreService(partnerRepo, orderRepo, incidentRepo, null);

        ReferringPartnerEntity clinic = service.registerReferringPartner(new RegisterReferringPartnerRequest(
                "CLIN-01", "Bonanjo Family Clinic", "CLINIC", "FHIR_API"));
        assertEquals("CLIN-01", clinic.getPartnerCode());

        DiagnosticServiceOrderEntity rejectedOrder = service.createOrder(new CreateDiagnosticOrderRequest(
                "ACC-LAB-01", "LABORATORY", "LOINC-2345-7", "WALKIN-44", "CLIN-01", 60));
        when(orderRepo.findByIdAndFacilityId(rejectedOrder.getId(), facId)).thenReturn(Optional.of(rejectedOrder));
        service.rejectAndRequestRecollection(rejectedOrder.getId(), "Hemolyzed specimen");
        assertTrue(rejectedOrder.isRecollectionRequested());
        assertThrows(IllegalStateException.class, () -> service.verifyAndReleaseReport(rejectedOrder.getId()));

        DiagnosticServiceOrderEntity delayedOrder = service.createOrder(new CreateDiagnosticOrderRequest(
                "ACC-RAD-01", "RADIOLOGY", "CPT-71046", "WALKIN-45", "CLIN-01", 30));
        delayedOrder.setOrderedAt(Instant.now().minus(90, ChronoUnit.MINUTES));
        when(orderRepo.findByIdAndFacilityId(delayedOrder.getId(), facId)).thenReturn(Optional.of(delayedOrder));
        service.verifyAndReleaseReport(delayedOrder.getId());
        assertEquals("VERIFIED_RELEASED", delayedOrder.getStatus());
        assertTrue(delayedOrder.isTatBreached());

        QualityIncidentEntity inc = service.logQualityIncident(new LogQualityIncidentRequest(
                "QI-15189-01", "PRE_ANALYTICAL", "ACC-LAB-01", "Hemolysis due to needle gauge mismatch", "qa.tech"));
        assertEquals("OPEN", inc.getStatus());
    }
}
