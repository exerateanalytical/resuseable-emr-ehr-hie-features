package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.publichealth.domain.PublicHealthEntities.ImmunizationRecordEntity;
import com.opeshealth.healthcore.publichealth.service.PublicHealthService;
import com.opeshealth.healthcore.publichealth.service.PublicHealthService.ChronicCascadeRepository;
import com.opeshealth.healthcore.publichealth.service.PublicHealthService.ImmunizationRepository;
import com.opeshealth.healthcore.starters.ProgramsImmunizationStarter.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProgramsImmunizationStarterTest {

    @Test
    void evaluatesWhoEpiCatchUpScheduleAndUnaidsCascadeMetrics() {
        UUID patientId = UUID.randomUUID();
        ImmunizationRepository immRepo = mock(ImmunizationRepository.class);
        ChronicCascadeRepository cascadeRepo = mock(ChronicCascadeRepository.class);

        ImmunizationRecordEntity bcg = new ImmunizationRecordEntity();
        bcg.setPatientId(patientId);
        bcg.setCvxCode("BCG");
        bcg.setDoseNumber(1);
        bcg.setNextDueDate(LocalDate.now().minusDays(30));
        when(immRepo.findAll()).thenReturn(List.of(bcg));
        when(cascadeRepo.findAll()).thenReturn(List.of());

        WhoEpiAndChronicCascadeService service = new WhoEpiAndChronicCascadeService(
                mock(PublicHealthService.class), immRepo, cascadeRepo);

        EpiPatientScheduleStatus schedule = service.evaluatePatientEpiSchedule(patientId);
        assertEquals(1, schedule.totalDosesRecorded());
        assertFalse(schedule.overdueCatchUpVaccines().isEmpty());
        assertNotNull(service.calculateUnaidsCascadeMetrics("HIV_ART"));
    }
}
