/*
 * =============================================================================
 * Opes HealthCore SDK — CoreModulesDataDrivenTestFixture Verification Suite
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.fixture;

import com.opeshealth.healthcore.analytics.service.HospitalAnalyticsService;
import com.opeshealth.healthcore.emergency.service.EmergencyNursingService;
import com.opeshealth.healthcore.emr.domain.EmrEntities.EncounterEntity;
import com.opeshealth.healthcore.emr.service.EmrClinicalWorkflowService.EncounterRepository;
import com.opeshealth.healthcore.governance.domain.TenantGovernanceEntities.TenantFacilityEntity;
import com.opeshealth.healthcore.governance.domain.TenantGovernanceEntities.TenantOrganizationEntity;
import com.opeshealth.healthcore.governance.service.TenantGovernanceService;
import com.opeshealth.healthcore.integrationops.domain.IntegrationOpsEntities.PartnerConformanceProfileEntity;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientAggregateEntity;
import com.opeshealth.healthcore.mpi.service.MpiCommandService;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.BloodUnitRepository;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.SurgicalScheduleRepository;
import com.opeshealth.healthcore.orders.domain.OrdersResultsEntities.ClinicalResultEntity;
import com.opeshealth.healthcore.orders.domain.OrdersResultsEntities.UnifiedClinicalOrderEntity;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService;
import com.opeshealth.healthcore.persistence.fixture.DataDrivenWorkflowFixtureLoader;
import com.opeshealth.healthcore.persistence.fixture.StandardWorkflowFixtureDataset;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService;
import com.opeshealth.healthcore.portal.service.PatientPortalService;
import com.opeshealth.healthcore.quickstart.fixture.CoreModulesDataDrivenTestFixture.PopulatedWorkflowContext;
import com.opeshealth.healthcore.radiology.service.RadiologyRisService;
import com.opeshealth.healthcore.revenueclaims.domain.RevenueCodingClaimsEntities.ClinicalCodingRecordEntity;
import com.opeshealth.healthcore.revenueclaims.domain.RevenueCodingClaimsEntities.InsuranceAuthorizationEntity;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService;
import com.opeshealth.healthcore.scheduling.domain.SchedulingEntities.ResourceCalendarEntity;
import com.opeshealth.healthcore.scheduling.domain.SchedulingEntities.ScheduledAppointmentEntity;
import com.opeshealth.healthcore.scheduling.service.SchedulingService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import com.opeshealth.healthcore.uhc.service.UhcBillingService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CoreModulesDataDrivenTestFixtureTest {

    @Test
    void automaticallyPopulatesAllCoreModulesFromStandardizedDatasetAndClearsFacilityContext() {
        TenantGovernanceService governanceService = mock(TenantGovernanceService.class);
        TenantGovernanceService.OrganizationRepository orgRepo = mock(TenantGovernanceService.OrganizationRepository.class);
        TenantGovernanceService.FacilityRepository facilityRepo = mock(TenantGovernanceService.FacilityRepository.class);
        MpiCommandService mpiService = mock(MpiCommandService.class);
        SchedulingService schedulingService = mock(SchedulingService.class);
        EncounterRepository encounterRepo = mock(EncounterRepository.class);
        UnifiedOrdersResultsService ordersResultsService = mock(UnifiedOrdersResultsService.class);
        PharmacyLisService pharmacyLisService = mock(PharmacyLisService.class);
        UhcBillingService billingService = mock(UhcBillingService.class);
        PatientPortalService portalService = mock(PatientPortalService.class);
        RadiologyRisService radiologyService = mock(RadiologyRisService.class);
        EmergencyNursingService emergencyNursingService = mock(EmergencyNursingService.class);
        IntegrationOpsService integrationOpsService = mock(IntegrationOpsService.class);
        RevenueCodingClaimsService revenueClaimsService = mock(RevenueCodingClaimsService.class);
        HospitalAnalyticsService analyticsService = mock(HospitalAnalyticsService.class);
        BloodUnitRepository bloodUnitRepo = mock(BloodUnitRepository.class);
        SurgicalScheduleRepository surgicalScheduleRepo = mock(SurgicalScheduleRepository.class);

        TenantOrganizationEntity org = mock(TenantOrganizationEntity.class);
        when(org.getId()).thenReturn(UUID.randomUUID());
        when(orgRepo.findByOrgCode(anyString())).thenReturn(Optional.empty());
        when(governanceService.createOrganization(any())).thenReturn(org);

        TenantFacilityEntity fac = mock(TenantFacilityEntity.class);
        UUID facId = UUID.randomUUID();
        when(fac.getId()).thenReturn(facId);
        when(facilityRepo.findByFacilityCode(anyString())).thenReturn(Optional.empty());
        when(governanceService.onboardFacility(any())).thenReturn(fac);

        when(mpiService.registerPatient(any())).thenAnswer(inv -> {
            PatientAggregateEntity p = mock(PatientAggregateEntity.class);
            when(p.getId()).thenReturn(UUID.randomUUID());
            return p;
        });

        when(schedulingService.createCalendar(eq(facId), any())).thenAnswer(inv -> {
            ResourceCalendarEntity cal = mock(ResourceCalendarEntity.class);
            when(cal.getId()).thenReturn(UUID.randomUUID());
            return cal;
        });
        when(schedulingService.bookAppointment(eq(facId), any(), anyString())).thenAnswer(inv -> {
            ScheduledAppointmentEntity appt = mock(ScheduledAppointmentEntity.class);
            when(appt.getId()).thenReturn(UUID.randomUUID());
            return appt;
        });

        when(encounterRepo.save(any(EncounterEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        when(ordersResultsService.placeOrder(eq(facId), any(), anyString())).thenAnswer(inv -> {
            UnifiedClinicalOrderEntity o = mock(UnifiedClinicalOrderEntity.class);
            when(o.getId()).thenReturn(UUID.randomUUID());
            return o;
        });
        when(ordersResultsService.enterResult(eq(facId), any(), anyString())).thenAnswer(inv -> {
            ClinicalResultEntity r = mock(ClinicalResultEntity.class);
            when(r.getId()).thenReturn(UUID.randomUUID());
            return r;
        });

        when(integrationOpsService.upsertPartnerProfile(any(), anyString())).thenAnswer(inv -> {
            PartnerConformanceProfileEntity partner = mock(PartnerConformanceProfileEntity.class);
            when(partner.getId()).thenReturn(UUID.randomUUID());
            return partner;
        });

        when(revenueClaimsService.createCodingAssignment(any())).thenAnswer(inv -> {
            ClinicalCodingRecordEntity coding = mock(ClinicalCodingRecordEntity.class);
            when(coding.getId()).thenReturn(UUID.randomUUID());
            return coding;
        });
        when(revenueClaimsService.verifyEligibilityAndRequestAuth(any(), anyString())).thenAnswer(inv -> {
            InsuranceAuthorizationEntity auth = mock(InsuranceAuthorizationEntity.class);
            when(auth.getId()).thenReturn(UUID.randomUUID());
            return auth;
        });

        CoreModulesDataDrivenTestFixture fixture = new CoreModulesDataDrivenTestFixture(
                governanceService, orgRepo, facilityRepo, mpiService, schedulingService,
                encounterRepo, ordersResultsService, pharmacyLisService, billingService,
                portalService, radiologyService, emergencyNursingService, integrationOpsService,
                revenueClaimsService, analyticsService, bloodUnitRepo, surgicalScheduleRepo
        );

        PopulatedWorkflowContext ctx = fixture.populateStandardDataset();

        ctx.assertCoreWorkflowsPopulated();
        assertEquals("healthcore-standard-workflows-v1", ctx.datasetId());
        assertEquals(facId, ctx.facilityId());
        assertEquals(3, ctx.patientIdsByMrn().size());
        assertEquals(2, ctx.encounterIdsByKey().size());
        assertEquals(2, ctx.orderIdsByKey().size());
        assertEquals(2, ctx.resultIdsByKey().size());
        assertEquals(1, ctx.partnerIdsByCode().size());
        assertEquals(1, ctx.populatedCounts().get("radiologyExams"));
        assertEquals(1, ctx.populatedCounts().get("edTriages"));
        assertEquals(1, ctx.populatedCounts().get("news2Observations"));
        assertEquals(1, ctx.populatedCounts().get("bloodUnits"));
        assertEquals(1, ctx.populatedCounts().get("surgicalSchedules"));
        assertEquals(1, ctx.populatedCounts().get("claims"));
        assertEquals(1, ctx.populatedCounts().get("qualityMeasures"));
        assertEquals(1, ctx.populatedCounts().get("cohortExports"));

        // Verify check-in was called only for appointments configured with checkIn=true
        verify(schedulingService, times(1)).checkIn(eq(facId), any());
        // Verify every order went through collectSpecimen -> enterResult -> verifyResult
        verify(ordersResultsService, times(2)).collectSpecimen(eq(facId), any(), any(), anyString());
        verify(ordersResultsService, times(2)).verifyResult(eq(facId), any(), anyString());
        // Verify coding -> physician approval -> eligibility auth -> claim submission pipeline
        verify(revenueClaimsService, times(1)).approveClinicalCoding(any(), eq("dr.syn.ngono"));
        verify(revenueClaimsService, times(1)).submitPayerClaim(any(), anyString());
        // Verify FacilityContext was cleanly cleared after fixture execution
        assertTrue(FacilityContext.getFacilityId().isEmpty());
    }

    @Test
    void supportsCustomInlineDatasetOverridesForTargetedWorkflowTests() {
        StandardWorkflowFixtureDataset custom = DataDrivenWorkflowFixtureLoader.loadFromJson("""
                {
                  "datasetId": "minimal-emr-billing-override",
                  "version": "1.0.0",
                  "syntheticOnly": true,
                  "tenant": {
                    "orgCode": "MIN-ORG",
                    "orgName": "Minimal Clinic Org",
                    "orgType": "CLINIC",
                    "countryCode": "CM",
                    "facilityCode": "MIN-FAC",
                    "facilityName": "Minimal Clinic",
                    "facilityType": "CLINIC",
                    "timezone": "Africa/Douala",
                    "currency": "XAF",
                    "language": "en",
                    "active": true,
                    "departments": [{ "code": "OPD", "name": "General OPD", "kind": "OUTPATIENT", "bedCapacity": 0 }],
                    "featureFlags": []
                  },
                  "patients": [{
                    "mrn": "MIN-PT-1", "familyName": "Clinic", "givenName": "Sample",
                    "birthDate": "1992-05-10", "sex": "female", "bloodGroup": "O+",
                    "phone": "+237600000001", "language": "en", "nationalIdType": "SYN",
                    "nationalIdNumber": "SYN1", "city": "Buea",
                    "emergencyContactName": "Kin", "emergencyContactPhone": "+237600000002"
                  }],
                  "encounters": [{
                    "encounterKey": "MIN-ENC-1", "patientMrn": "MIN-PT-1",
                    "encounterClass": "AMB", "status": "IN_PROGRESS",
                    "attendingId": "dr.min", "departmentCode": "OPD", "primaryIcd10": "J06.9"
                  }],
                  "billingCharges": [{
                    "patientMrn": "MIN-PT-1", "amountMinorUnits": 5000,
                    "currency": "XAF", "invoiceRef": "MIN-INV-1", "description": "OPD Visit"
                  }]
                }
                """);

        assertEquals("minimal-emr-billing-override", custom.datasetId());
        assertEquals(1, custom.patients().size());
        assertEquals(1, custom.encounters().size());
        assertEquals(1, custom.billingCharges().size());
    }
}
