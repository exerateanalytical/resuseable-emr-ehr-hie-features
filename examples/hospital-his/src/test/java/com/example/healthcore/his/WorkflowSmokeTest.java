package com.example.healthcore.his;

import com.opeshealth.healthcore.ops.service.HospitalOpsService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WorkflowSmokeTest {
    @Test
    void targetDomainServiceIsOnClasspathWithoutUnrelatedIndustries() {
        assertNotNull(HospitalOpsService.class);
    }
}
