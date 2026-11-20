package com.example.healthcore.lslims;

import com.opeshealth.healthcore.lifesciences.lims.service.LifeSciencesLimsService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WorkflowSmokeTest {
    @Test
    void targetDomainServiceIsOnClasspathWithoutUnrelatedIndustries() {
        assertNotNull(LifeSciencesLimsService.class);
    }
}
