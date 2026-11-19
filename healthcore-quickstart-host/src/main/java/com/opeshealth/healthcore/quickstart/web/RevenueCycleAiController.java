/*
 * =============================================================================
 * Opes HealthCore SDK — Prior Auth & Claim Denial Appeal AI REST Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.quickstart.service.RevenueCycleAiService;
import com.opeshealth.healthcore.quickstart.service.RevenueCycleAiService.RevenueNarrativeDraftResponse;
import com.opeshealth.healthcore.quickstart.service.RevenueCycleAiService.RevenueNarrativeRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * REST Controller for Capability 6: Prior Authorization &amp; Claim Denial Appeal Writer.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/ai/revenue")
@Tag(name = "Clinical AI — Prior Auth & Claim Denial Appeals",
        description = "Drafts payer medical necessity narratives and CARC/RARC claim denial appeal letters from redacted patient chart evidence")
public class RevenueCycleAiController {

    private final RevenueCycleAiService revenueAiService;

    public RevenueCycleAiController(RevenueCycleAiService revenueAiService) {
        this.revenueAiService = Objects.requireNonNull(revenueAiService);
    }

    @PostMapping(value = "/narratives", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Generate a payer-ready Prior Authorization narrative or CARC/RARC claim denial appeal letter")
    public ResponseEntity<RevenueNarrativeDraftResponse> draftNarrative(@RequestBody RevenueNarrativeRequest request) {
        return ResponseEntity.status(201).body(revenueAiService.draftPriorAuthOrAppeal(request));
    }
}
