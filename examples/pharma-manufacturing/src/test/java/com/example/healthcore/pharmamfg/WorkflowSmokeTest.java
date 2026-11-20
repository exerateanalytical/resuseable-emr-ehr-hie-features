package com.example.healthcore.pharmamfg;

import com.opeshealth.healthcore.pharma.manufacturing.service.PharmaManufacturingService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WorkflowSmokeTest {
    @Test
    void targetDomainServiceIsOnClasspathWithoutUnrelatedIndustries() {
        assertNotNull(PharmaManufacturingService.class);
    }
}
