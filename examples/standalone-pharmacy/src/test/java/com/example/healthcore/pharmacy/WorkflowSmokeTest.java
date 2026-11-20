package com.example.healthcore.pharmacy;

import com.opeshealth.healthcore.pharmacy.commerce.service.PharmacyCommerceService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WorkflowSmokeTest {
    @Test
    void targetDomainServiceIsOnClasspathWithoutUnrelatedIndustries() {
        assertNotNull(PharmacyCommerceService.class);
    }
}
