/*
 * =============================================================================
 * Opes HealthCore SDK — Multimodal Diagnostic Image Triage REST Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.quickstart.service.MultimodalImageTriageAiService;
import com.opeshealth.healthcore.quickstart.service.MultimodalImageTriageAiService.ImageTriageDraftResponse;
import com.opeshealth.healthcore.quickstart.service.MultimodalImageTriageAiService.ImageTriageRequest;
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
 * REST Controller for Capability 4: Multimodal Diagnostic &amp; Clinical Image Triage.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/ai/vision")
@Tag(name = "Clinical AI — Multimodal Diagnostic & Image Triage",
        description = "Structured clinician-review triage of wound photos, dermatology captures, scanned lab slips, and ECG strips")
public class MultimodalImageTriageController {

    private final MultimodalImageTriageAiService visionService;

    public MultimodalImageTriageController(MultimodalImageTriageAiService visionService) {
        this.visionService = Objects.requireNonNull(visionService);
    }

    @PostMapping(value = "/triage", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Analyze a clinical image alongside redacted vitals to produce structured clinician-review findings")
    public ResponseEntity<ImageTriageDraftResponse> triageClinicalImage(@RequestBody ImageTriageRequest request) {
        return ResponseEntity.status(201).body(visionService.analyzeClinicalImage(request));
    }
}
