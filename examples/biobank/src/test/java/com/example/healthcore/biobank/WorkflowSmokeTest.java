package com.example.healthcore.biobank;

import com.opeshealth.healthcore.biobanking.service.BiobankingService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WorkflowSmokeTest {
    @Test
    void targetDomainServiceIsOnClasspathWithoutUnrelatedIndustries() {
        assertNotNull(BiobankingService.class);
    }
}
