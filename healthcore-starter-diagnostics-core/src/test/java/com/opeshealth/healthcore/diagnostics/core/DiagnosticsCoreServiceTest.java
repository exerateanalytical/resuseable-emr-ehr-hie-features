package com.opeshealth.healthcore.diagnostics.core;

import com.opeshealth.healthcore.diagnostics.core.domain.DiagnosticsCoreEntities.*;
import com.opeshealth.healthcore.diagnostics.core.service.DiagnosticsCoreService;
import com.opeshealth.healthcore.diagnostics.core.service.DiagnosticsCoreService.*;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.springframework.beans.factory.ObjectProvider;
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

        @SuppressWarnings("unchecked")
        ObjectProvider<DiagnosticsQcPolicySpi> qcPolicyProvider = mock(ObjectProvider.class);
        // Unit fixture: explicitly supply a synthetic approved QC policy; production defaults fail closed.
        when(qcPolicyProvider.getIfAvailable(any())).thenReturn(order -> true);
        DiagnosticsCoreService service = new DiagnosticsCoreService(
                partnerRepo, catalogRepo, orderRepo, critRepo, amendRepo, incidentRepo, qcPolicyProvider, null);

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


    @Test
    void unconfiguredQcMustNotReleaseEvenAnOtherwiseValidOrder() {
        UUID facility = UUID.randomUUID();
        FacilityContext.setFacilityId(facility);
        DiagnosticServiceOrderRepository orders = mock(DiagnosticServiceOrderRepository.class);
        DiagnosticServiceOrderEntity registered = new DiagnosticServiceOrderEntity();
        registered.setFacilityId(facility);
        registered.setAccessionNumber("QC-UNAVAILABLE-001");
        registered.setStatus("REGISTERED");
        when(orders.findByIdAndFacilityId(registered.getId(), facility)).thenReturn(Optional.of(registered));

        DiagnosticsCoreService service = new DiagnosticsCoreService(
                mock(ReferringPartnerRepository.class), mock(DiagnosticCatalogRepository.class),
                orders, mock(CriticalNotificationRepository.class),
                mock(ResultAmendmentRepository.class), mock(QualityIncidentRepository.class),
                null, null);
        assertThrows(IllegalStateException.class,
                () -> service.verifyAndReleaseReport(registered.getId()));
        assertNull(registered.getVerifiedAt());
        verify(orders, never()).save(any());
    }

    @Test
    void blocksUnconfiguredOrNoncriticalEscalationAndUnreleasedAmendment() {
        UUID facility = UUID.randomUUID();
        FacilityContext.setFacilityId(facility);

        ReferringPartnerRepository partners = mock(ReferringPartnerRepository.class);
        DiagnosticCatalogRepository catalog = mock(DiagnosticCatalogRepository.class);
        DiagnosticServiceOrderRepository orders = mock(DiagnosticServiceOrderRepository.class);
        CriticalNotificationRepository alerts = mock(CriticalNotificationRepository.class);
        ResultAmendmentRepository amendments = mock(ResultAmendmentRepository.class);
        QualityIncidentRepository incidents = mock(QualityIncidentRepository.class);
        when(orders.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DiagnosticsCoreService service = new DiagnosticsCoreService(
                partners, catalog, orders, alerts, amendments, incidents, null, null);
        DiagnosticServiceOrderEntity order = service.createOrder(new CreateDiagnosticOrderRequest(
                "SAFE-001", "LABORATORY", "LOINC-2823-3", "SYNTHETIC-1", null, 30));
        when(orders.findByIdAndFacilityId(order.getId(), facility)).thenReturn(Optional.of(order));

        EscalateCriticalResultRequest request = new EscalateCriticalResultRequest(
                order.getId(), new BigDecimal("4.1"), "Potassium", "clinician-01");
        assertThrows(IllegalStateException.class, () -> service.escalateCriticalResult(request));
        DiagnosticCatalogItemEntity potassium = new DiagnosticCatalogItemEntity();
        potassium.setCatalogCode("LOINC-2823-3");
        potassium.setCriticalHigh(new BigDecimal("6.2"));
        potassium.setCriticalLow(new BigDecimal("2.5"));
        when(catalog.findByFacilityIdAndCatalogCode(facility, order.getCatalogCode()))
                .thenReturn(Optional.of(potassium));
        assertThrows(IllegalArgumentException.class, () -> service.escalateCriticalResult(request));

        assertThrows(IllegalStateException.class, () -> service.amendVerifiedResult(new AmendResultRequest(
                order.getId(), "Old report", "New report", "Analyzer correction", "scientist-01")));
        assertThrows(IllegalArgumentException.class, () -> service.escalateCriticalResult(
                new EscalateCriticalResultRequest(order.getId(), null, "No measured value", "clinician-01")));
        verify(alerts, never()).save(any());
        verify(amendments, never()).save(any());
    }

    @Test
    void readBackRetriesPreserveFirstClinicianTimestampAndReleasedOrderCannotBeRejected() {
        UUID facility = UUID.randomUUID();
        FacilityContext.setFacilityId(facility);
        DiagnosticServiceOrderRepository orders = mock(DiagnosticServiceOrderRepository.class);
        CriticalNotificationRepository alerts = mock(CriticalNotificationRepository.class);
        DiagnosticsCoreService service = new DiagnosticsCoreService(
                mock(ReferringPartnerRepository.class), mock(DiagnosticCatalogRepository.class),
                orders, alerts, mock(ResultAmendmentRepository.class),
                mock(QualityIncidentRepository.class), null, null);

        CriticalValueNotificationEntity notification = new CriticalValueNotificationEntity();
        notification.setFacilityId(facility);
        notification.setNotifiedClinician("doctor-1");
        when(alerts.findByIdAndFacilityId(notification.getId(), facility))
                .thenReturn(Optional.of(notification));
        when(alerts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThrows(IllegalStateException.class, () -> service.acknowledgeCriticalReadBack(
                notification.getId(), "wrong-doctor", true));
        assertThrows(IllegalArgumentException.class, () -> service.acknowledgeCriticalReadBack(
                notification.getId(), " ", true));
        assertNull(notification.getAcknowledgedAt());

        CriticalValueNotificationEntity first = service.acknowledgeCriticalReadBack(
                notification.getId(), "doctor-1", true);
        Instant firstAcknowledged = first.getAcknowledgedAt();
        assertNotNull(firstAcknowledged);
        assertSame(first, service.acknowledgeCriticalReadBack(notification.getId(), "doctor-1", true));
        assertEquals(firstAcknowledged, notification.getAcknowledgedAt());
        assertThrows(IllegalStateException.class, () -> service.acknowledgeCriticalReadBack(
                notification.getId(), "doctor-2", true));
        verify(alerts, times(1)).save(notification);

        DiagnosticServiceOrderEntity released = new DiagnosticServiceOrderEntity();
        released.setFacilityId(facility);
        released.setStatus("VERIFIED_RELEASED");
        released.setVerifiedAt(Instant.now());
        when(orders.findByIdAndFacilityId(released.getId(), facility)).thenReturn(Optional.of(released));
        assertThrows(IllegalStateException.class, () -> service.rejectAndRequestRecollection(
                released.getId(), "late rejection"));
        assertSame(released, service.verifyAndReleaseReport(released.getId()));
        verify(orders, never()).save(released);
    }

}
