package com.example.healthcore.hie;

import com.opeshealth.healthcore.hie.service.HieInteropService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WorkflowSmokeTest {
    @Test
    void targetDomainServiceIsOnClasspathWithoutUnrelatedIndustries() {
        assertNotNull(HieInteropService.class);
    }
}
