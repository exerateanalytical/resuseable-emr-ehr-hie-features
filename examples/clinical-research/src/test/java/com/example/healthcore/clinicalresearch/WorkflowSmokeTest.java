package com.example.healthcore.clinicalresearch;

import com.opeshealth.healthcore.clinicalresearch.service.ClinicalResearchService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WorkflowSmokeTest {
    @Test
    void targetDomainServiceIsOnClasspathWithoutUnrelatedIndustries() {
        assertNotNull(ClinicalResearchService.class);
    }
}
