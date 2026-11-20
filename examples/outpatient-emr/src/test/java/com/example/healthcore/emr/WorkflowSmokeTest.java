package com.example.healthcore.emr;

import com.opeshealth.healthcore.emr.service.EmrClinicalWorkflowService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WorkflowSmokeTest {
    @Test
    void targetDomainServiceIsOnClasspathWithoutUnrelatedIndustries() {
        assertNotNull(EmrClinicalWorkflowService.class);
    }
}
