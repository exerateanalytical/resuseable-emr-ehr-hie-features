package com.opeshealth.healthcore.governance;

import com.opeshealth.healthcore.governance.domain.TenantGovernanceEntities.*;
import com.opeshealth.healthcore.governance.service.TenantGovernanceService;
import com.opeshealth.healthcore.governance.service.TenantGovernanceService.*;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TenantGovernanceServiceTest {

    private OrganizationRepository orgRepo;
    private FacilityRepository facilityRepo;
    private DepartmentRepository deptRepo;
    private FeatureFlagRepository flagRepo;
    private CrossFacilityGrantRepository grantRepo;
    private TenantGovernanceService service;

    @BeforeEach
    void setUp() {
        orgRepo = mock(OrganizationRepository.class);
        facilityRepo = mock(FacilityRepository.class);
        deptRepo = mock(DepartmentRepository.class);
        flagRepo = mock(FeatureFlagRepository.class);
        grantRepo = mock(CrossFacilityGrantRepository.class);
        when(orgRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(facilityRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(deptRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(flagRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(grantRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new TenantGovernanceService(orgRepo, facilityRepo, deptRepo, flagRepo, grantRepo, mock(ClinicalAuditService.class));
    }

    @Test
    void createsOrganizationOnboardsFacilityAndEnforcesLifecycleAndFeatureFlags() {
        TenantOrganizationEntity org = service.createOrganization(
                new CreateOrganizationRequest("OPES-NET", "Opes Hospital Network", "HOSPITAL_NETWORK", "CM"));
        assertEquals("OPES-NET", org.getOrgCode());
        when(orgRepo.findById(org.getId())).thenReturn(Optional.of(org));

        TenantFacilityEntity fac = service.onboardFacility(new OnboardFacilityRequest(
                org.getId(), "DLA-REF", "Douala Reference Hospital", "TERTIARY", "CM",
                "Africa/Douala", "XAF", "fr", true));
        assertEquals(FacilityLifecycleStatus.ACTIVE, fac.getLifecycleStatus());
        when(facilityRepo.findById(fac.getId())).thenReturn(Optional.of(fac));

        TenantFacilityEntity suspended = service.transitionFacilityStatus(
                fac.getId(), FacilityLifecycleStatus.SUSPENDED, "Compliance review", "admin.1");
        assertEquals(FacilityLifecycleStatus.SUSPENDED, suspended.getLifecycleStatus());

        FacilityFeatureFlagEntity flag = service.upsertFeatureFlag(fac.getId(), "clinical.ai", true, true, "admin.1");
        assertTrue(flag.isKillSwitch());
        when(flagRepo.findByFacilityIdAndFlagKey(fac.getId(), "clinical.ai")).thenReturn(Optional.of(flag));
        assertFalse(service.isFeatureEnabled(fac.getId(), "clinical.ai", false));
    }

    @Test
    void enforcesMaxGrantDurationForCrossFacilityGrants() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        TenantFacilityEntity sFac = new TenantFacilityEntity();
        sFac.setFacilityCode("SRC");
        TenantFacilityEntity tFac = new TenantFacilityEntity();
        tFac.setFacilityCode("TGT");
        when(facilityRepo.findById(source)).thenReturn(Optional.of(sFac));
        when(facilityRepo.findById(target)).thenReturn(Optional.of(tFac));

        assertThrows(IllegalArgumentException.class, () -> service.issueCrossFacilityGrant(
                source,
                new IssueCrossFacilityGrantRequest(target, "dr.consult", UUID.randomUUID(), "READ_ONLY",
                        "Long consult", Instant.now().plus(90, ChronoUnit.DAYS)),
                "admin.1"));

        CrossFacilityAccessGrantEntity grant = service.issueCrossFacilityGrant(
                source,
                new IssueCrossFacilityGrantRequest(target, "dr.consult", UUID.randomUUID(), "READ_ONLY",
                        "Specialist referral", Instant.now().plus(7, ChronoUnit.DAYS)),
                "admin.1");
        assertFalse(grant.isRevoked());
    }
}
