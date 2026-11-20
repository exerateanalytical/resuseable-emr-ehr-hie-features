package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.TelemedicineSessionEntity;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.TelemedicineRepository;
import com.opeshealth.healthcore.starters.TelemedicineStarter.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TelemedicineStarterTest {

    @Test
    void provisionsSessionAndDowngradesToAudioOrStoreAndForwardOnLowBandwidth() {
        TelemedicineRepository repo = mock(TelemedicineRepository.class);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        TelemedicineVirtualCareService service = new TelemedicineVirtualCareService(repo);

        TelemedicineSessionEntity session = service.provisionVirtualSession(
                new TelemedicineProvisionRequest(UUID.randomUUID(), UUID.randomUUID(), "dr.tele", 1200));
        assertNotNull(session.getRoomTokenHash());
        when(repo.findById(session.getId())).thenReturn(Optional.of(session));

        AdaptiveBandwidthDecision degraded = service.evaluateAndAdaptBandwidth(
                session.getId(), new NetworkTelemetryUpdate(40, 12.0, 950));
        assertNotNull(degraded.recommendedMode());
        assertNotEquals("HD_VIDEO", degraded.recommendedMode());
    }
}
