/*
 * =============================================================================
 * Opes HealthCore SDK — HealthCoreDataFixture Isolation & Full-Suite Tests
 * =============================================================================
 */
package com.opeshealth.healthcore.testing;

import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService;
import com.opeshealth.healthcore.analytics.domain.AnalyticsEntities.ClinicalQualityMeasureEntity;
import com.opeshealth.healthcore.analytics.domain.AnalyticsEntities.CohortExportRequestEntity;
import com.opeshealth.healthcore.analytics.service.HospitalAnalyticsService;
import com.opeshealth.healthcore.emergency.domain.EmergencyNursingEntities.DischargeReadinessEntity;
import com.opeshealth.healthcore.emergency.domain.EmergencyNursingEntities.EmergencyVisitEntity;
import com.opeshealth.healthcore.emergency.domain.EmergencyNursingEntities.News2ObservationEntity;
import com.opeshealth.healthcore.emergency.service.EmergencyNursingService;
import com.opeshealth.healthcore.emr.domain.EmrEntities.EncounterEntity;
import com.opeshealth.healthcore.emr.service.EmrClinicalWorkflowService.EncounterRepository;
import com.opeshealth.healthcore.governance.domain.TenantGovernanceEntities.TenantFacilityEntity;
import com.opeshealth.healthcore.governance.domain.TenantGovernanceEntities.TenantOrganizationEntity;
import com.opeshealth.healthcore.governance.service.TenantGovernanceService;
import com.opeshealth.healthcore.integrationops.domain.IntegrationOpsEntities.IntegrationMessageLedgerEntity;
import com.opeshealth.healthcore.integrationops.domain.IntegrationOpsEntities.PartnerConformanceProfileEntity;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientAggregateEntity;
import com.opeshealth.healthcore.mpi.service.MpiCommandService;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.BloodComponentUnitEntity;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.SurgicalScheduleEntity;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.BloodUnitRepository;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.SurgicalScheduleRepository;
import com.opeshealth.healthcore.orders.domain.OrdersResultsEntities.ClinicalResultEntity;
import com.opeshealth.healthcore.orders.domain.OrdersResultsEntities.UnifiedClinicalOrderEntity;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService;
import com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities.LabSpecimenEntity;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService;
import com.opeshealth.healthcore.portal.service.PatientPortalService;
import com.opeshealth.healthcore.radiology.domain.RadiologyRisEntities.ModalityWorklistEntity;
import com.opeshealth.healthcore.radiology.domain.RadiologyRisEntities.RadiologyReportEntity;
import com.opeshealth.healthcore.radiology.service.RadiologyRisService;
import com.opeshealth.healthcore.revenueclaims.domain.RevenueCodingClaimsEntities.ClinicalCodingRecordEntity;
import com.opeshealth.healthcore.revenueclaims.domain.RevenueCodingClaimsEntities.FacilityTariffEntity;
import com.opeshealth.healthcore.revenueclaims.domain.RevenueCodingClaimsEntities.InsuranceAuthorizationEntity;
import com.opeshealth.healthcore.revenueclaims.domain.RevenueCodingClaimsEntities.PayerClaimEntity;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService;
import com.opeshealth.healthcore.queue.domain.QueueManagementEntities.DepartmentQueueEntryEntity;
import com.opeshealth.healthcore.queue.service.QueueManagementService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import com.opeshealth.healthcore.starters.BloodBankStarter;
import com.opeshealth.healthcore.starters.SurgeryOtStarter;
import com.opeshealth.healthcore.testing.HealthCoreDataFixture.ConsoleSeedSummary;
import com.opeshealth.healthcore.testing.HealthCoreDataFixture.SeededHospitalFixtureResult;
import com.opeshealth.healthcore.testing.data.IsoHl7StandardSampleData;
import com.opeshealth.healthcore.workflow.domain.WorkflowEntities.WorkflowInstanceEntity;
import com.opeshealth.healthcore.workflow.journey.ClinicalJourneyContracts.BillingClearanceDecision;
import com.opeshealth.healthcore.workflow.journey.ClinicalJourneyContracts.PaymentClearanceOutcome;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyEntities.DepartmentOrderRoutingEntity;
import com.opeshealth.healthcore.workflow.journey.PatientJourneyOrchestratorService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HealthCoreDataFixtureTest {

    @Test
    void seedsIntegrationOpsStarterInCompleteIsolation() {
        UUID facId = UUID.randomUUID();
        IntegrationOpsService integrationOps = mock(IntegrationOpsService.class);
        when(integrationOps.upsertPartnerProfile(any(), anyString())).thenAnswer(i -> {
            PartnerConformanceProfileEntity p = mock(PartnerConformanceProfileEntity.class);
            when(p.getId()).thenReturn(UUID.randomUUID());
            return p;
        });
        when(integrationOps.enqueueOutbound(any(), anyString())).thenAnswer(i -> {
            IntegrationMessageLedgerEntity m = mock(IntegrationMessageLedgerEntity.class);
            when(m.getId()).thenReturn(UUID.randomUUID());
            return m;
        });

        HealthCoreDataFixture isolatedFixture = HealthCoreDataFixture.builder()
                .integrationOpsService(integrationOps)
                .build();

        ConsoleSeedSummary summary = isolatedFixture.seedIntegrationOpsStarter(facId);

        assertEquals("integration-ops", summary.consoleKey());
        assertEquals(4, summary.operationsExecuted());
        verify(integrationOps, times(2)).upsertPartnerProfile(any(), anyString());
        verify(integrationOps, times(2)).enqueueOutbound(any(), anyString());
        verify(integrationOps, times(1)).recordDeliveryFailure(any(), any(), anyString());
        verify(integrationOps, times(1)).replayMessage(any(), any());
        assertTrue(FacilityContext.getFacilityId().isEmpty());
    }

    @Test
    void seedsRevenueCodingClaimsStarterInCompleteIsolation() {
        UUID facId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();

        RevenueCodingClaimsService revService = mock(RevenueCodingClaimsService.class);
        when(revService.upsertTariff(any(), anyString())).thenAnswer(i -> {
            FacilityTariffEntity t = mock(FacilityTariffEntity.class);
            when(t.getId()).thenReturn(UUID.randomUUID());
            return t;
        });
        when(revService.createCodingAssignment(any())).thenAnswer(i -> {
            ClinicalCodingRecordEntity c = mock(ClinicalCodingRecordEntity.class);
            when(c.getId()).thenReturn(UUID.randomUUID());
            return c;
        });
        when(revService.verifyEligibilityAndRequestAuth(any(), anyString())).thenAnswer(i -> {
            InsuranceAuthorizationEntity a = mock(InsuranceAuthorizationEntity.class);
            when(a.getId()).thenReturn(UUID.randomUUID());
            return a;
        });
        when(revService.submitPayerClaim(any(), anyString())).thenAnswer(i -> {
            PayerClaimEntity cl = mock(PayerClaimEntity.class);
            when(cl.getId()).thenReturn(UUID.randomUUID());
            return cl;
        });

        HealthCoreDataFixture isolatedFixture = HealthCoreDataFixture.builder()
                .revenueClaimsService(revService)
                .build();

        ConsoleSeedSummary summary = isolatedFixture.seedRevenueCodingClaimsStarter(facId, patientId, encounterId);

        assertEquals("revenue-coding-claims", summary.consoleKey());
        assertEquals(5, summary.operationsExecuted());
        verify(revService, times(1)).approveClinicalCoding(any(), eq("dr.iso.ngono"));
        verify(revService, times(1)).submitPayerClaim(any(), anyString());
        verify(revService, times(1)).postEraRemittance(any(), any(), anyString());
        assertTrue(FacilityContext.getFacilityId().isEmpty());
    }

    @Test
    void seedsAllEightReferenceConsolesAndBothNewStartersWithIsoHl7Data() {
        TenantGovernanceService gov = mock(TenantGovernanceService.class);
        TenantGovernanceService.OrganizationRepository orgRepo = mock(TenantGovernanceService.OrganizationRepository.class);
        TenantGovernanceService.FacilityRepository facRepo = mock(TenantGovernanceService.FacilityRepository.class);
        MpiCommandService mpi = mock(MpiCommandService.class);
        EncounterRepository encRepo = mock(EncounterRepository.class);
        RadiologyRisService rad = mock(RadiologyRisService.class);
        EmergencyNursingService ed = mock(EmergencyNursingService.class);
        UnifiedOrdersResultsService orders = mock(UnifiedOrdersResultsService.class);
        PharmacyLisService pharmLis = mock(PharmacyLisService.class);
        BloodBankStarter.BloodBankHemovigilanceService bloodSvc = mock(BloodBankStarter.BloodBankHemovigilanceService.class);
        BloodUnitRepository bloodRepo = mock(BloodUnitRepository.class);
        SurgeryOtStarter.SurgicalPerioperativeService otSvc = mock(SurgeryOtStarter.SurgicalPerioperativeService.class);
        SurgicalScheduleRepository otRepo = mock(SurgicalScheduleRepository.class);
        PatientPortalService portal = mock(PatientPortalService.class);
        IntegrationOpsService intOps = mock(IntegrationOpsService.class);
        ClinicalAiGovernanceService aiGov = new ClinicalAiGovernanceService();
        HospitalAnalyticsService analytics = mock(HospitalAnalyticsService.class);
        RevenueCodingClaimsService rev = mock(RevenueCodingClaimsService.class);

        TenantOrganizationEntity org = mock(TenantOrganizationEntity.class);
        when(org.getId()).thenReturn(UUID.randomUUID());
        when(orgRepo.findByOrgCode(IsoHl7StandardSampleData.ORG_CODE)).thenReturn(Optional.empty());
        when(gov.createOrganization(any())).thenReturn(org);

        TenantFacilityEntity fac = mock(TenantFacilityEntity.class);
        UUID facId = UUID.randomUUID();
        when(fac.getId()).thenReturn(facId);
        when(facRepo.findByFacilityCode(IsoHl7StandardSampleData.FACILITY_CODE)).thenReturn(Optional.empty());
        when(gov.onboardFacility(any())).thenReturn(fac);

        when(mpi.registerPatient(any())).thenAnswer(i -> {
            PatientAggregateEntity p = mock(PatientAggregateEntity.class);
            when(p.getId()).thenReturn(UUID.randomUUID());
            return p;
        });
        when(encRepo.save(any(EncounterEntity.class))).thenAnswer(i -> i.getArgument(0));

        when(rad.scheduleImagingExam(any())).thenAnswer(i -> {
            ModalityWorklistEntity item = mock(ModalityWorklistEntity.class);
            when(item.getId()).thenReturn(UUID.randomUUID());
            return item;
        });
        when(rad.signReport(any())).thenAnswer(i -> {
            RadiologyReportEntity rep = mock(RadiologyReportEntity.class);
            when(rep.getId()).thenReturn(UUID.randomUUID());
            return rep;
        });

        when(ed.registerEdTriage(any())).thenAnswer(i -> {
            EmergencyVisitEntity v = mock(EmergencyVisitEntity.class);
            when(v.getId()).thenReturn(UUID.randomUUID());
            return v;
        });
        when(ed.recordNews2Observation(any())).thenAnswer(i -> {
            News2ObservationEntity n = mock(News2ObservationEntity.class);
            when(n.getId()).thenReturn(UUID.randomUUID());
            return n;
        });
        when(ed.updateDischargeReadiness(any())).thenAnswer(i -> {
            DischargeReadinessEntity d = mock(DischargeReadinessEntity.class);
            when(d.getId()).thenReturn(UUID.randomUUID());
            return d;
        });

        when(orders.placeOrder(eq(facId), any(), anyString())).thenAnswer(i -> {
            UnifiedClinicalOrderEntity o = mock(UnifiedClinicalOrderEntity.class);
            when(o.getId()).thenReturn(UUID.randomUUID());
            return o;
        });
        when(orders.enterResult(eq(facId), any(), anyString())).thenAnswer(i -> {
            ClinicalResultEntity r = mock(ClinicalResultEntity.class);
            when(r.getId()).thenReturn(UUID.randomUUID());
            return r;
        });
        when(pharmLis.accessionSpecimen(any(LabSpecimenEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(bloodRepo.save(any(BloodComponentUnitEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(otRepo.save(any(SurgicalScheduleEntity.class))).thenAnswer(i -> i.getArgument(0));

        when(intOps.upsertPartnerProfile(any(), anyString())).thenAnswer(i -> {
            PartnerConformanceProfileEntity p = mock(PartnerConformanceProfileEntity.class);
            when(p.getId()).thenReturn(UUID.randomUUID());
            return p;
        });
        when(intOps.enqueueOutbound(any(), anyString())).thenAnswer(i -> {
            IntegrationMessageLedgerEntity m = mock(IntegrationMessageLedgerEntity.class);
            when(m.getId()).thenReturn(UUID.randomUUID());
            return m;
        });

        when(analytics.recordQualityMeasure(any())).thenAnswer(i -> {
            ClinicalQualityMeasureEntity q = mock(ClinicalQualityMeasureEntity.class);
            when(q.getId()).thenReturn(UUID.randomUUID());
            return q;
        });
        when(analytics.requestCohortExport(any())).thenAnswer(i -> {
            CohortExportRequestEntity e = mock(CohortExportRequestEntity.class);
            when(e.getId()).thenReturn(UUID.randomUUID());
            return e;
        });

        when(rev.upsertTariff(any(), anyString())).thenAnswer(i -> {
            FacilityTariffEntity t = mock(FacilityTariffEntity.class);
            when(t.getId()).thenReturn(UUID.randomUUID());
            return t;
        });
        when(rev.createCodingAssignment(any())).thenAnswer(i -> {
            ClinicalCodingRecordEntity c = mock(ClinicalCodingRecordEntity.class);
            when(c.getId()).thenReturn(UUID.randomUUID());
            return c;
        });
        when(rev.verifyEligibilityAndRequestAuth(any(), anyString())).thenAnswer(i -> {
            InsuranceAuthorizationEntity a = mock(InsuranceAuthorizationEntity.class);
            when(a.getId()).thenReturn(UUID.randomUUID());
            return a;
        });
        when(rev.submitPayerClaim(any(), anyString())).thenAnswer(i -> {
            PayerClaimEntity cl = mock(PayerClaimEntity.class);
            when(cl.getId()).thenReturn(UUID.randomUUID());
            return cl;
        });

        HealthCoreDataFixture fixture = HealthCoreDataFixture.builder()
                .governance(gov, orgRepo, facRepo)
                .mpiService(mpi)
                .encounterRepo(encRepo)
                .radiologyService(rad)
                .emergencyNursingService(ed)
                .ordersResultsService(orders)
                .pharmacyLisService(pharmLis)
                .bloodBankService(bloodSvc)
                .bloodUnitRepo(bloodRepo)
                .surgeryService(otSvc)
                .surgicalScheduleRepo(otRepo)
                .portalService(portal)
                .integrationOpsService(intOps)
                .aiGovernanceService(aiGov)
                .analyticsService(analytics)
                .revenueClaimsService(rev)
                .build();

        SeededHospitalFixtureResult result = fixture.seedAllReferenceConsolesAndStarters();

        result.assertAllConsolesAndStartersSeeded();
        assertEquals(9, result.consoleSummaries().size());
        assertEquals(1, aiGov.listDrafts(facId, null).size());
        assertTrue(FacilityContext.getFacilityId().isEmpty());
    }

    @Test
    void seedsMasterPatientJourneyAndDepartmentalQueuesInIsolation() {
        UUID facId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();

        PatientJourneyOrchestratorService journeySvc = mock(PatientJourneyOrchestratorService.class);
        QueueManagementService queueSvc = mock(QueueManagementService.class);

        WorkflowInstanceEntity parentWf = mock(WorkflowInstanceEntity.class);
        when(parentWf.getId()).thenReturn(UUID.randomUUID());
        when(journeySvc.startParentOutpatientJourney(eq(facId), eq(patientId), eq(encounterId), eq(true), eq(false), anyString()))
                .thenReturn(parentWf);
        when(journeySvc.evaluateTriageAndConsultationClearance(eq(facId), eq(patientId), eq(encounterId),
                eq(parentWf.getId()), eq(3), eq(false), any(), any(), anyString()))
                .thenReturn(new BillingClearanceDecision(PaymentClearanceOutcome.INSURANCE_AUTHORIZED, true, false, true, "Pre-authorized"));

        DepartmentQueueEntryEntity qEntry = mock(DepartmentQueueEntryEntity.class);
        when(qEntry.getId()).thenReturn(UUID.randomUUID());
        when(queueSvc.enqueue(eq(facId), any(), anyString())).thenReturn(qEntry);

        DepartmentOrderRoutingEntity routedOrder = mock(DepartmentOrderRoutingEntity.class);
        when(routedOrder.getId()).thenReturn(UUID.randomUUID());
        when(journeySvc.routeSignedOrder(any(), any())).thenReturn(routedOrder);

        HealthCoreDataFixture isolatedFixture = HealthCoreDataFixture.builder()
                .patientJourneyService(journeySvc)
                .queueManagementService(queueSvc)
                .build();

        ConsoleSeedSummary summary = isolatedFixture.seedMasterPatientJourneyAndQueues(facId, patientId, encounterId);

        assertEquals("patient-journey-queues", summary.consoleKey());
        assertEquals(4, summary.operationsExecuted());
        assertEquals(3, summary.createdIds().size());
        verify(queueSvc, times(1)).claimTask(eq(facId), eq(qEntry.getId()), eq("dr.iso.ngono"));
        verify(journeySvc, times(1)).routeSignedOrder(any(), any());
        assertTrue(FacilityContext.getFacilityId().isEmpty());
    }
}
