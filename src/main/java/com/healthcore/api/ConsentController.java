package com.healthcore.api;

import com.healthcore.consent.ConsentEnforcementService;
import com.healthcore.consent.PatientConsentEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/hie/consents")
public class ConsentController {

    private final ConsentEnforcementService consentService;

    public ConsentController(ConsentEnforcementService consentService) {
        this.consentService = consentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('CLINICIAN', 'REGISTRAR', 'ADMIN')")
    public PatientConsentEntity grantConsent(@Valid @RequestBody GrantConsentRequest req, Authentication auth) {
        return consentService.recordConsent(
                req.patientId(),
                req.grantingFacilityId(),
                req.targetFacilityId(),
                req.scope(),
                req.purposeOfUse(),
                req.expiresAt(),
                auth.getName()
        );
    }

    public record GrantConsentRequest(
            @NotNull UUID patientId,
            UUID grantingFacilityId,
            UUID targetFacilityId,
            @NotBlank String scope,
            @NotBlank String purposeOfUse,
            Instant expiresAt
    ) {}
}
