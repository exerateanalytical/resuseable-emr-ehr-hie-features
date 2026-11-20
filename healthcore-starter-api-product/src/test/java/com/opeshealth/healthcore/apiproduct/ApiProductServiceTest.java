package com.opeshealth.healthcore.apiproduct;

import com.opeshealth.healthcore.apiproduct.service.ApiProductService;
import com.opeshealth.healthcore.apiproduct.service.ApiProductService.DeveloperAppEntity;
import com.opeshealth.healthcore.apiproduct.service.ApiProductService.DeveloperAppRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApiProductServiceTest {

    private DeveloperAppRepository appRepo;
    private ApiProductService service;

    @BeforeEach
    void setUp() {
        appRepo = mock(DeveloperAppRepository.class);
        when(appRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new ApiProductService(appRepo);
    }

    @Test
    void registersAppSignsWebhooksRotatesKeyAndRevokes() {
        Map<String, Object> reg = service.registerDeveloperApp("PartnerDiagnostics", "ENTERPRISE",
                "https://partner.example.org/webhooks", false);
        assertNotNull(reg.get("apiKey"));
        assertNotNull(reg.get("appId"));

        String sig1 = service.signWebhookPayload("{\"event\":\"ORDER_SIGNED\"}", "secret-key-1");
        String sig2 = service.signWebhookPayload("{\"event\":\"ORDER_SIGNED\"}", "secret-key-1");
        assertEquals(sig1, sig2);

        DeveloperAppEntity app = new DeveloperAppEntity();
        app.setPartnerName("PartnerDiagnostics");
        app.setActive(true);
        when(appRepo.findById(app.getId())).thenReturn(Optional.of(app));

        Map<String, Object> rotated = service.rotateApiKey(app.getId());
        assertNotNull(rotated.get("apiKey"));

        DeveloperAppEntity revoked = service.revokeApp(app.getId());
        assertFalse(revoked.isActive());
    }
}
