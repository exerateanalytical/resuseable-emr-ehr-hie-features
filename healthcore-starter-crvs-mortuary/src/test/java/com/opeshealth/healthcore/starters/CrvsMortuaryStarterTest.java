package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.CrvsBirthDeathEntity;
import com.opeshealth.healthcore.ops.service.HospitalOpsService;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.CrvsRepository;
import com.opeshealth.healthcore.starters.CrvsMortuaryStarter.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import com.opeshealth.healthcore.security.tenant.FacilityContext;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CrvsMortuaryStarterTest {

    @AfterEach
    void clearFacility() { FacilityContext.clear(); }

    @Test
    void registersWhoMccdDeathAndEnforcesDualSignOffForMortuaryRelease() {
        HospitalOpsService opsService = mock(HospitalOpsService.class);
        CrvsRepository crvsRepo = mock(CrvsRepository.class);
        when(crvsRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        UUID facilityId = UUID.randomUUID();
        FacilityContext.setFacilityId(facilityId);

        CrvsAndMortuaryChainOfCustodyService service = new CrvsAndMortuaryChainOfCustodyService(opsService, crvsRepo);
        CrvsBirthDeathEntity death = service.validateAndRegisterWhoMccdDeath(new WhoMccdDeathRegistrationRequest(
                facilityId, UUID.randomUUID(), "I46.9", "J18.9", "E11.9", "dr.certifier", "BAY-M04", "MCCD-REFERENCE-001"));
        assertNotNull(death.getCertificateNumber());

        when(crvsRepo.findByIdAndFacilityId(death.getId(), facilityId)).thenReturn(Optional.of(death));
        assertThrows(SecurityException.class, () -> service.verifyAndReleaseMortuaryCustody(
                new MortuaryChainOfCustodyReleaseRequest(death.getId(), "dr.1", "dr.1", "Next Of Kin", "NID-99")));

        MortuaryReleaseReceipt receipt = service.verifyAndReleaseMortuaryCustody(
                new MortuaryChainOfCustodyReleaseRequest(death.getId(), "dr.1", "mort.officer", "Next Of Kin", "NID-99"));
        assertEquals("CUSTODY_RELEASED_AND_AUDITED", receipt.releaseStatus());
        assertEquals("RELEASED", death.getMortuaryCustodyStatus());
        assertEquals("BAY-M04", death.getMortuaryCompartment());
    }
}
