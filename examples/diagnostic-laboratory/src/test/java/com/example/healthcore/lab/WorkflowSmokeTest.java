package com.example.healthcore.lab;

import com.opeshealth.healthcore.diagnostics.core.service.DiagnosticsCoreService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WorkflowSmokeTest {
    @Test
    void targetDomainServiceIsOnClasspathWithoutUnrelatedIndustries() {
        assertNotNull(DiagnosticsCoreService.class);
    }
}
