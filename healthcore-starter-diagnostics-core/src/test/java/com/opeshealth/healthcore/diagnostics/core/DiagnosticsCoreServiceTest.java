package com.opeshealth.healthcore.diagnostics.core;

import com.opeshealth.healthcore.diagnostics.core.domain.DiagnosticsCoreEntities.*;
import com.opeshealth.healthcore.diagnostics.core.service.DiagnosticsCoreService;
import com.opeshealth.healthcore.diagnostics.core.service.DiagnosticsCoreService.*;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
    void enforcesRejectionGateCriticalReadBackAmendmentAuditAndTatSlaDetection() {
        UUID facId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);

        ReferringPartnerRepository partnerRepo = mock(ReferringPartnerRepository.class);
        DiagnosticCatalogRepository catalogRepo = mock(DiagnosticCatalogRepository.class);
        DiagnosticServiceOrderRepository orderRepo = mock(DiagnosticServiceOrderRepository.class);
        CriticalNotificationRepository critRepo = mock(CriticalNotificationRepository.class);
        ResultAmendmentRepository amendRepo = mock(ResultAmendmentRepository.class);
        QualityIncidentRepository incidentRepo = mock(QualityIncidentRepository.class);

        when(partnerRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(catalogRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(orderRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(critRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(amendRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(incidentRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        DiagnosticsCoreService service = new DiagnosticsCoreService(
                partnerRepo, catalogRepo, orderRepo, critRepo, amendRepo, incidentRepo, null, null);

        ReferringPartnerEntity clinic = service.registerReferringPartner(new RegisterReferringPartnerRequest(
                "CLIN-01", "Bonanjo Family Clinic", "CLINIC", "FHIR_API"));
        assertEquals("CLIN-01", clinic.getPartnerCode());

        DiagnosticCatalogItemEntity potassium = service.registerCatalogItem(new RegisterCatalogItemRequest(
                "LOINC-2823-3", "Serum Potassium", "LABORATORY", false, "mmol/L",
                BigDecimal.valueOf(3.5), BigDecimal.valueOf(5.1), BigDecimal.valueOf(2.5), BigDecimal.valueOf(6.2), 60, null));
        when(catalogRepo.findByFacilityIdAndCatalogCode(facId, "LOINC-2823-3")).thenReturn(Optional.of(potassium));

        DiagnosticServiceOrderEntity rejectedOrder = service.createOrder(new CreateDiagnosticOrderRequest(
                "ACC-LAB-01", "LABORATORY", "LOINC-2823-3", "WALKIN-44", "CLIN-01", 60));
        when(orderRepo.findByIdAndFacilityId(rejectedOrder.getId(), facId)).thenReturn(Optional.of(rejectedOrder));
        service.rejectAndRequestRecollection(rejectedOrder.getId(), "Hemolyzed specimen");
        assertTrue(rejectedOrder.isRecollectionRequested());
        assertThrows(IllegalStateException.class, () -> service.verifyAndReleaseReport(rejectedOrder.getId()));

        // Workflow 3: Critical value escalation + mandatory read-back acknowledgment
        DiagnosticServiceOrderEntity criticalOrder = service.createOrder(new CreateDiagnosticOrderRequest(
                "ACC-LAB-02", "LABORATORY", "LOINC-2823-3", "WALKIN-44", "CLIN-01", 30));
        criticalOrder.setOrderedAt(Instant.now().minus(90, ChronoUnit.MINUTES));
        when(orderRepo.findByIdAndFacilityId(criticalOrder.getId(), facId)).thenReturn(Optional.of(criticalOrder));

        CriticalValueNotificationEntity crit = service.escalateCriticalResult(new EscalateCriticalResultRequest(
                criticalOrder.getId(), BigDecimal.valueOf(6.9), "K+ = 6.9 mmol/L (CRITICAL HIGH)", "Dr. Kamga"));
        when(critRepo.findByIdAndFacilityId(crit.getId(), facId)).thenReturn(Optional.of(crit));
        assertThrows(IllegalStateException.class, () -> service.acknowledgeCriticalReadBack(crit.getId(), "Dr. Kamga", false));
        service.acknowledgeCriticalReadBack(crit.getId(), "Dr. Kamga", true);
        assertEquals("ACKNOWLEDGED_READBACK", crit.getStatus());

        service.verifyAndReleaseReport(criticalOrder.getId());
        assertEquals("VERIFIED_RELEASED", criticalOrder.getStatus());
        assertTrue(criticalOrder.isTatBreached());

        // Immutable result amendment
        ResultAmendmentEntity amendment = service.amendVerifiedResult(new AmendResultRequest(
                criticalOrder.getId(), "K+ = 6.9 mmol/L", "K+ = 6.8 mmol/L (re-run confirmed)", "Duplicate analyzer confirmation", "pathologist.nkeng"));
        assertEquals("AMENDED", criticalOrder.getStatus());
        assertEquals("pathologist.nkeng", amendment.getAmendedBy());

        QualityIncidentEntity inc = service.logQualityIncident(new LogQualityIncidentRequest(
                "QI-15189-01", "PRE_ANALYTICAL", "ACC-LAB-01", "Hemolysis due to needle gauge mismatch", "qa.tech"));
        assertEquals("OPEN", inc.getStatus());
    }
}
