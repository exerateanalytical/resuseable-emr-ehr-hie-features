package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.CrvsBirthDeathEntity;
import com.opeshealth.healthcore.ops.service.HospitalOpsService;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.CrvsRepository;
import com.opeshealth.healthcore.starters.CrvsMortuaryStarter.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CrvsMortuaryStarterTest {

    @Test
    void registersWhoMccdDeathAndEnforcesDualSignOffForMortuaryRelease() {
        HospitalOpsService opsService = mock(HospitalOpsService.class);
        CrvsRepository crvsRepo = mock(CrvsRepository.class);
        when(crvsRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(opsService.registerVitalEvent(any())).thenAnswer(i -> i.getArgument(0));

        CrvsAndMortuaryChainOfCustodyService service = new CrvsAndMortuaryChainOfCustodyService(opsService, crvsRepo);
        CrvsBirthDeathEntity death = service.validateAndRegisterWhoMccdDeath(new WhoMccdDeathRegistrationRequest(
                UUID.randomUUID(), UUID.randomUUID(), "I46.9", "J18.9", "E11.9", "dr.certifier", "BAY-M04"));
        assertNotNull(death.getCertificateNumber());

        when(crvsRepo.findById(death.getId())).thenReturn(Optional.of(death));
        assertThrows(IllegalArgumentException.class, () -> service.verifyAndReleaseMortuaryCustody(
                new MortuaryChainOfCustodyReleaseRequest(death.getId(), "dr.1", "dr.1", "Next Of Kin", "NID-99")));

        MortuaryReleaseReceipt receipt = service.verifyAndReleaseMortuaryCustody(
                new MortuaryChainOfCustodyReleaseRequest(death.getId(), "dr.1", "mort.officer", "Next Of Kin", "NID-99"));
        assertEquals("RELEASED", receipt.releaseStatus());
    }
}
