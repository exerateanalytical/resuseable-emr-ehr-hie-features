/*
 * =============================================================================
 * Opes HealthCore SDK — Automated Medical Coding AI REST Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.quickstart.service.MedicalCodingAiService;
import com.opeshealth.healthcore.quickstart.service.MedicalCodingAiService.MedicalCodingDraftResponse;
import com.opeshealth.healthcore.quickstart.service.MedicalCodingAiService.MedicalCodingRequest;
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
 * REST Controller for Capability 2: Automated Medical Coding &amp; DRG/UHC Billing Assistant.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/ai/coding")
@Tag(name = "Clinical AI — Automated Medical Coding & DRG/UHC Billing",
        description = "Extracts ICD-10, ICD-11, SNOMED-CT, LOINC, CPT, and DRG/UHC codes with clinical evidence spans and CDI queries")
public class MedicalCodingAiController {

    private final MedicalCodingAiService codingService;

    public MedicalCodingAiController(MedicalCodingAiService codingService) {
        this.codingService = Objects.requireNonNull(codingService);
    }

    @PostMapping(value = "/suggest", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Extract ICD-10/ICD-11/SNOMED/LOINC/CPT codes with supporting text spans from redacted clinical notes")
    public ResponseEntity<MedicalCodingDraftResponse> suggestCodes(@RequestBody MedicalCodingRequest request) {
        return ResponseEntity.status(201).body(codingService.suggestMedicalCodes(request));
    }
}
