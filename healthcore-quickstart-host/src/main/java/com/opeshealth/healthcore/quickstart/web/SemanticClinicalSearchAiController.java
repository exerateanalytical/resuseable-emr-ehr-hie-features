/*
 * =============================================================================
 * Opes HealthCore SDK — Semantic Clinical Search & Cohort Matching Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.quickstart.service.SemanticClinicalSearchAiService;
import com.opeshealth.healthcore.quickstart.service.SemanticClinicalSearchAiService.IndexDocumentRequest;
import com.opeshealth.healthcore.quickstart.service.SemanticClinicalSearchAiService.IndexedClinicalVectorRecord;
import com.opeshealth.healthcore.quickstart.service.SemanticClinicalSearchAiService.SemanticSearchRequest;
import com.opeshealth.healthcore.quickstart.service.SemanticClinicalSearchAiService.SemanticSearchResponse;
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
 * REST Controller for Capability 9: Semantic Chart Search &amp; Clinical Trial Cohort Matching
 * powered by {@code gemini-embedding-2}.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/ai/semantic")
@Tag(name = "Clinical AI — Semantic Chart Search & Trial Cohort Matching",
        description = "Safe-Harbor redacted clinical embeddings (gemini-embedding-2) and tenant-scoped cosine-similarity search across charts and trial protocols")
public class SemanticClinicalSearchAiController {

    private final SemanticClinicalSearchAiService semanticSearchService;

    public SemanticClinicalSearchAiController(SemanticClinicalSearchAiService semanticSearchService) {
        this.semanticSearchService = Objects.requireNonNull(semanticSearchService);
    }

    @PostMapping(value = "/index", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Redact and index a clinical note, lab/radiology report, or trial protocol using gemini-embedding-2")
    public ResponseEntity<IndexedClinicalVectorRecord> indexDocument(@RequestBody IndexDocumentRequest request) {
        return ResponseEntity.status(201).body(semanticSearchService.indexClinicalDocument(request));
    }

    @PostMapping(value = "/search", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Execute semantic vector search across a facility's indexed charts or clinical trial protocols")
    public ResponseEntity<SemanticSearchResponse> searchSimilar(@RequestBody SemanticSearchRequest request) {
        return ResponseEntity.ok(semanticSearchService.searchClinicallySimilar(request));
    }
}
