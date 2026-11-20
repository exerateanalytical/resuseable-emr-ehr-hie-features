/*
 * =============================================================================
 * Opes HealthCore SDK — WorkflowVerificationService Tests
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
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService.PartnerHealthSummary;
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
import com.opeshealth.healthcore.portal.domain.PatientPortalEntities.PortalMessageThreadEntity;
import com.opeshealth.healthcore.portal.service.PatientPortalService;
import com.opeshealth.healthcore.radiology.domain.RadiologyRisEntities.ModalityWorklistEntity;
import com.opeshealth.healthcore.radiology.domain.RadiologyRisEntities.RadiologyReportEntity;
import com.opeshealth.healthcore.radiology.service.RadiologyRisService;
import com.opeshealth.healthcore.revenueclaims.domain.RevenueCodingClaimsEntities.ClinicalCodingRecordEntity;
import com.opeshealth.healthcore.revenueclaims.domain.RevenueCodingClaimsEntities.FacilityTariffEntity;
import com.opeshealth.healthcore.revenueclaims.domain.RevenueCodingClaimsEntities.InsuranceAuthorizationEntity;
import com.opeshealth.healthcore.revenueclaims.domain.RevenueCodingClaimsEntities.PayerClaimEntity;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService.ClaimsSummaryReport;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import com.opeshealth.healthcore.starters.BloodBankStarter;
import com.opeshealth.healthcore.starters.BloodBankStarter.CompatibilityEvaluation;
import com.opeshealth.healthcore.starters.SurgeryOtStarter;
import com.opeshealth.healthcore.starters.SurgeryOtStarter.PacuDischargeDecision;
import com.opeshealth.healthcore.testing.WorkflowVerificationService.WorkflowExecutionResult;
import com.opeshealth.healthcore.testing.WorkflowVerificationService.WorkflowVerificationReport;
import com.opeshealth.healthcore.testing.data.IsoHl7StandardSampleData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowVerificationServiceTest {

    @Test
    void seedsAndVerifiesAllNineCoreReadmeWorkflowsSuccessfully() {
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

        UUID facId = UUID.randomUUID();
        TenantOrganizationEntity org = mock(TenantOrganizationEntity.class);
        when(org.getId()).thenReturn(UUID.randomUUID());
        when(orgRepo.findByOrgCode(IsoHl7StandardSampleData.ORG_CODE)).thenReturn(Optional.empty());
        when(gov.createOrganization(any())).thenReturn(org);

        TenantFacilityEntity fac = mock(TenantFacilityEntity.class);
        when(fac.getId()).thenReturn(facId);
        when(facRepo.findByFacilityCode(IsoHl7StandardSampleData.FACILITY_CODE)).thenReturn(Optional.empty());
        when(gov.onboardFacility(any())).thenReturn(fac);

        when(mpi.registerPatient(any())).thenAnswer(i -> {
            PatientAggregateEntity p = mock(PatientAggregateEntity.class);
            when(p.getId()).thenReturn(UUID.randomUUID());
            return p;
        });
        when(encRepo.save(any(EncounterEntity.class))).thenAnswer(i -> i.getArgument(0));

        // Radiology
        ModalityWorklistEntity worklistItem = mock(ModalityWorklistEntity.class);
        when(worklistItem.getId()).thenReturn(UUID.randomUUID());
        RadiologyReportEntity radReport = mock(RadiologyReportEntity.class);
        when(radReport.getId()).thenReturn(UUID.randomUUID());
        when(rad.scheduleImagingExam(any())).thenReturn(worklistItem);
        when(rad.signReport(any())).thenReturn(radReport);
        when(rad.listWorklist("COMPLETED")).thenReturn(List.of(worklistItem));
        when(rad.listUnacknowledgedCriticalFindings()).thenReturn(List.of());

        // Emergency & Nursing
        EmergencyVisitEntity edVisit = mock(EmergencyVisitEntity.class);
        when(edVisit.getId()).thenReturn(UUID.randomUUID());
        News2ObservationEntity news2 = mock(News2ObservationEntity.class);
        when(news2.getId()).thenReturn(UUID.randomUUID());
        DischargeReadinessEntity gate = mock(DischargeReadinessEntity.class);
        when(gate.getId()).thenReturn(UUID.randomUUID());
        when(ed.registerEdTriage(any())).thenReturn(edVisit);
        when(ed.recordNews2Observation(any())).thenReturn(news2);
        when(ed.updateDischargeReadiness(any())).thenReturn(gate);
        when(ed.getActiveEdBoard()).thenReturn(List.of(edVisit));
        when(ed.listActiveEscalations()).thenReturn(List.of(news2));

        // Laboratory
        UnifiedClinicalOrderEntity order = mock(UnifiedClinicalOrderEntity.class);
        when(order.getId()).thenReturn(UUID.randomUUID());
        ClinicalResultEntity res = mock(ClinicalResultEntity.class);
        when(res.getId()).thenReturn(UUID.randomUUID());
        when(orders.placeOrder(eq(facId), any(), anyString())).thenReturn(order);
        when(orders.enterResult(eq(facId), any(), anyString())).thenReturn(res);
        when(orders.listPatientOrders(eq(facId), any())).thenReturn(List.of(order));
        when(orders.listPatientResults(eq(facId), any())).thenReturn(List.of(res));
        when(pharmLis.accessionSpecimen(any(LabSpecimenEntity.class))).thenAnswer(i -> i.getArgument(0));

        // Blood Bank & OT
        when(bloodRepo.save(any(BloodComponentUnitEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(otRepo.save(any(SurgicalScheduleEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(bloodSvc.evaluateCompatibility("O-", "AB+", "PRBC")).thenReturn(
                new CompatibilityEvaluation("O-", "AB+", "PRBC", true, true, "Compatible universal PRBC donor"));
        when(otSvc.evaluateAndRecordPacuAldreteScore(any())).thenReturn(
                new PacuDischargeDecision(UUID.randomUUID(), UUID.randomUUID(), 10, true, "ELIGIBLE", "Cleared for ward"));

        // Patient Portal
        when(portal.listPatientThreads(eq(facId), anyString(), any())).thenReturn(List.of(mock(PortalMessageThreadEntity.class)));

        // Integration Ops
        PartnerConformanceProfileEntity partner1 = mock(PartnerConformanceProfileEntity.class);
        when(partner1.getId()).thenReturn(UUID.randomUUID());
        PartnerConformanceProfileEntity partner2 = mock(PartnerConformanceProfileEntity.class);
        when(partner2.getId()).thenReturn(UUID.randomUUID());
        when(intOps.upsertPartnerProfile(any(), anyString())).thenReturn(partner1, partner2);
        when(intOps.enqueueOutbound(any(), anyString())).thenAnswer(i -> {
            IntegrationMessageLedgerEntity m = mock(IntegrationMessageLedgerEntity.class);
            when(m.getId()).thenReturn(UUID.randomUUID());
            return m;
        });
        when(intOps.listPartners()).thenReturn(List.of(partner1, partner2));
        when(intOps.getPartnerHealthSummaries()).thenReturn(List.of(
                new PartnerHealthSummary(partner1.getId(), "ISO-NAT-HIE-FHIR", "HIE", "FHIR_R4", "CLOSED", 0, 1, 1, 0)));

        // Analytics
        ClinicalQualityMeasureEntity measure = mock(ClinicalQualityMeasureEntity.class);
        when(measure.getId()).thenReturn(UUID.randomUUID());
        CohortExportRequestEntity exportReq = mock(CohortExportRequestEntity.class);
        when(exportReq.getId()).thenReturn(UUID.randomUUID());
        when(analytics.recordQualityMeasure(any())).thenReturn(measure);
        when(analytics.requestCohortExport(any())).thenReturn(exportReq);
        when(analytics.listQualityMeasures()).thenReturn(List.of(measure));
        when(analytics.listCohortExports()).thenReturn(List.of(exportReq));

        // Revenue Coding & Claims
        FacilityTariffEntity tariff = mock(FacilityTariffEntity.class);
        when(tariff.getId()).thenReturn(UUID.randomUUID());
        ClinicalCodingRecordEntity coding = mock(ClinicalCodingRecordEntity.class);
        when(coding.getId()).thenReturn(UUID.randomUUID());
        InsuranceAuthorizationEntity auth = mock(InsuranceAuthorizationEntity.class);
        when(auth.getId()).thenReturn(UUID.randomUUID());
        PayerClaimEntity claim = mock(PayerClaimEntity.class);
        when(claim.getId()).thenReturn(UUID.randomUUID());
        when(rev.upsertTariff(any(), anyString())).thenReturn(tariff);
        when(rev.createCodingAssignment(any())).thenReturn(coding);
        when(rev.verifyEligibilityAndRequestAuth(any(), anyString())).thenReturn(auth);
        when(rev.submitPayerClaim(any(), anyString())).thenReturn(claim);
        when(rev.quoteTariffPrice(IsoHl7StandardSampleData.PAYER_CNPS_UHC, "CONS-CARDIO")).thenReturn(25000L);
        when(rev.getClaimsSummary()).thenReturn(new ClaimsSummaryReport(1, 0, 1, 0, 48500L, 43500L, 0L));

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

        WorkflowVerificationService verifier = WorkflowVerificationService.builder(fixture)
                .radiologyService(rad)
                .emergencyNursingService(ed)
                .ordersResultsService(orders)
                .bloodBankService(bloodSvc)
                .surgeryService(otSvc)
                .portalService(portal)
                .integrationOpsService(intOps)
                .aiGovernanceService(aiGov)
                .analyticsService(analytics)
                .revenueClaimsService(rev)
                .build();

        WorkflowVerificationReport report = verifier.seedAndVerifyAllCoreReadmeWorkflows();

        report.assertAllPassed();
        assertEquals(9, report.passedCount());
        assertEquals(0, report.failedCount());
        assertTrue(report.toMarkdownSummary().contains("9/9 PASSED"));
        assertTrue(FacilityContext.getFacilityId().isEmpty());
    }

    @Test
    void verifiesIsolatedIntegrationOpsAndLogsFailureCleanlyWhenWorkflowFails() {
        UUID facId = UUID.randomUUID();
        IntegrationOpsService intOps = mock(IntegrationOpsService.class);
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
        // Simulate missing partners to trigger clean failure logging
        when(intOps.listPartners()).thenReturn(List.of());

        HealthCoreDataFixture fixture = HealthCoreDataFixture.builder()
                .integrationOpsService(intOps)
                .build();
        WorkflowVerificationService verifier = WorkflowVerificationService.builder(fixture)
                .integrationOpsService(intOps)
                .build();

        WorkflowExecutionResult failed = verifier.seedAndVerifyIsolatedWorkflow("integration-ops", facId, null, null);
        assertFalse(failed.passed());
        assertTrue(failed.detail().contains("Expected >= 2 registered FHIR R4 & HL7 v2.5.1 partner profiles"));
        assertTrue(FacilityContext.getFacilityId().isEmpty());

        // Now return valid partners + health summary and confirm PASS
        PartnerConformanceProfileEntity p1 = mock(PartnerConformanceProfileEntity.class);
        PartnerConformanceProfileEntity p2 = mock(PartnerConformanceProfileEntity.class);
        when(intOps.listPartners()).thenReturn(List.of(p1, p2));
        when(intOps.getPartnerHealthSummaries()).thenReturn(List.of(
                new PartnerHealthSummary(UUID.randomUUID(), "ISO-NAT-HIE-FHIR", "HIE", "FHIR_R4", "CLOSED", 0, 1, 1, 0)));

        WorkflowExecutionResult passed = verifier.seedAndVerifyIsolatedWorkflow("integration-ops", facId, null, null);
        assertTrue(passed.passed());
        assertTrue(passed.detail().contains("Verified 2 trading partner(s)"));
        assertTrue(FacilityContext.getFacilityId().isEmpty());
    }
}
