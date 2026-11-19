/*
 * =============================================================================
 * Opes HealthCore SDK — Longitudinal Chart & IPS Summarizer REST Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.quickstart.service.ChartSummarizationAiService;
import com.opeshealth.healthcore.quickstart.service.ChartSummarizationAiService.ChartSummaryDraftResponse;
import com.opeshealth.healthcore.quickstart.service.ChartSummarizationAiService.ChartSummaryRequest;
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
 * REST Controller for Capability 3: Longitudinal Chart &amp; IPS Summarizer.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/ai/summaries")
@Tag(name = "Clinical AI — Longitudinal Chart & IPS Summarizer",
        description = "Generates DS4P-aware SBAR handoff briefs, ED triage briefs, IPS executive summaries, and plain-language discharge summaries")
public class ChartSummarizationAiController {

    private final ChartSummarizationAiService summaryService;

    public ChartSummarizationAiController(ChartSummarizationAiService summaryService) {
        this.summaryService = Objects.requireNonNull(summaryService);
    }

    @PostMapping(value = "/generate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Generate an SBAR, ED Brief, IPS, or Patient Discharge summary from a DS4P-filtered patient chart")
    public ResponseEntity<ChartSummaryDraftResponse> generateSummary(@RequestBody ChartSummaryRequest request) {
        return ResponseEntity.status(201).body(summaryService.summarizePatientChart(request));
    }
}
