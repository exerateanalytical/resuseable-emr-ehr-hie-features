package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.publichealth.service.PublicHealthService;
import com.opeshealth.healthcore.publichealth.service.PublicHealthService.*;
import com.opeshealth.healthcore.starters.ChwHomeCareStarter.*;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChwHomeCareStarterTest {

    @Test
    void detectsSevereAcuteMalnutritionAndFastBreathingReferral() {
        PublicHealthService phService = mock(PublicHealthService.class);
        when(phService.recordChwVisit(any())).thenAnswer(i -> i.getArgument(0));

        ChwIccmAndHomeCareTriageService service = new ChwIccmAndHomeCareTriageService(
                phService, mock(ChwHouseholdRepository.class), mock(ChwVisitRepository.class),
                mock(HomeCarePlanRepository.class));

        IccmTriageOutcome outcome = service.evaluateIccmPediatricVisit(new IccmPediatricTriageInput(
                UUID.randomUUID(), UUID.randomUUID(), 18, 54, 110, true, true, true));
        assertTrue(outcome.fastBreathingPneumoniaSuspected());
        assertTrue(outcome.urgentHospitalReferralRequired());
        assertNotNull(outcome.referralQrToken());
        assertFalse(outcome.recommendedCommunityTreatments().isEmpty());
    }
}
