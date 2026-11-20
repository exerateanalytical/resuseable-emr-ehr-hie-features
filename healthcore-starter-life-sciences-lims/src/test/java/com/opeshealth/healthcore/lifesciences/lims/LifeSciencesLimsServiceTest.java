package com.opeshealth.healthcore.lifesciences.lims;

import com.opeshealth.healthcore.lifesciences.lims.domain.LifeSciencesLimsEntities.*;
import com.opeshealth.healthcore.lifesciences.lims.service.LifeSciencesLimsService;
import com.opeshealth.healthcore.lifesciences.lims.service.LifeSciencesLimsService.*;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LifeSciencesLimsServiceTest {

    @AfterEach
    void clearTenant() {
        FacilityContext.clear();
    }

    @Test
    void enforcesEthicsGateNonPatientScientificSamplesAndLockedElnSignOff() {
        UUID facId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);

        ResearchStudyRepository studyRepo = mock(ResearchStudyRepository.class);
        ScientificSampleRepository sampleRepo = mock(ScientificSampleRepository.class);
        ElnExperimentRepository elnRepo = mock(ElnExperimentRepository.class);
        when(studyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(sampleRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(elnRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        LifeSciencesLimsService service = new LifeSciencesLimsService(studyRepo, sampleRepo, elnRepo, null);

        ResearchStudyEntity study = service.createStudy(new CreateStudyRequest(
                "STUDY-MAL-2026", "Plasmodium Drug Resistance Assay", "Dr. Amina", "v1.2"));
        when(studyRepo.findByIdAndFacilityId(study.getId(), facId)).thenReturn(Optional.of(study));

        // Blocked before Ethics/IRB approval
        assertThrows(IllegalStateException.class, () -> service.registerScientificSample(
                new RegisterSampleRequest(study.getId(), "SMP-001", "CELL_LINE", "CL-3D7", "PARASITE_CULTURE", null, "tech.1", "INC-2")));

        service.approveEthicsAndActivate(study.getId(), "IRB-CM-2026-044");
        assertEquals("ACTIVE", study.getStatus());

        ScientificSampleEntity sample = service.registerScientificSample(
                new RegisterSampleRequest(study.getId(), "SMP-001", "CELL_LINE", "CL-3D7", "PARASITE_CULTURE", null, "tech.1", "INC-2"));
        assertEquals("CELL_LINE", sample.getSourceClassification());

        ElnExperimentEntity exp = service.recordElnExperiment(new RecordElnExperimentRequest(
                study.getId(), "EXP-01", "LCMS-02", "## Protocol\nIC50 assay completed.", "{\"ic50Nm\":12.4}", "scientist.sam"));
        when(elnRepo.findByIdAndFacilityId(exp.getId(), facId)).thenReturn(Optional.of(exp));

        // Author cannot self-review ELN
        assertThrows(SecurityException.class, () -> service.reviewAndSignEln(exp.getId(), "scientist.sam"));

        service.reviewAndSignEln(exp.getId(), "pi.amina");
        assertEquals("SIGNED_LOCKED", exp.getStatus());
        assertNotNull(exp.getReviewSigSha256());

        // Locked ELN cannot be mutated
        assertThrows(IllegalStateException.class, () -> service.amendElnExperiment(exp.getId(), "Tampered text", "{}"));
    }
}
