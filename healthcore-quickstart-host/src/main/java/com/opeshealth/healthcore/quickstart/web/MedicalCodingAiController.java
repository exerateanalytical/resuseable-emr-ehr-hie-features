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

import com.opeshealth.healthcore.ai.coding.ClinicalCodingEngine;
import com.opeshealth.healthcore.ai.coding.ClinicalCodingEngine.BillingComplianceReport;
import com.opeshealth.healthcore.ai.coding.ClinicalCodingEngine.ClinicalCodingRequest;
import com.opeshealth.healthcore.ai.coding.ClinicalCodingEngine.ClinicalCodingResult;
import com.opeshealth.healthcore.ai.coding.ClinicalCodingEngine.RedactedCodingContext;
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

import java.util.Map;
import java.util.Objects;

/**
 * REST Controller for Capability 2: Automated Medical Coding &amp; DRG/UHC Billing Assistant,
 * including regulation-compliant ICD-10-CM/CPT automation powered by {@link ClinicalCodingEngine}.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/ai/coding")
@Tag(name = "Clinical AI — Automated Medical Coding & DRG/UHC Billing",
        description = "Extracts ICD-10-CM, CPT, HCPCS, ICD-11, SNOMED-CT, and LOINC codes with NCCI/billing compliance validation, Safe-Harbor PHI redaction, and CDI queries")
public class MedicalCodingAiController {

    public record ComplianceAuditResponse(
            int totalFhirResourcesProcessed,
            BillingComplianceReport complianceReport,
            int totalPhiRedactions,
            Map<String, Integer> redactionCountsByCategory
    ) {}

    private final MedicalCodingAiService codingService;
    private final ClinicalCodingEngine clinicalCodingEngine;

    public MedicalCodingAiController(
            MedicalCodingAiService codingService,
            ClinicalCodingEngine clinicalCodingEngine) {
        this.codingService = Objects.requireNonNull(codingService);
        this.clinicalCodingEngine = Objects.requireNonNull(clinicalCodingEngine);
    }

    @PostMapping(value = "/suggest", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Extract ICD-10/ICD-11/SNOMED/LOINC/CPT codes with supporting text spans from redacted clinical notes")
    public ResponseEntity<MedicalCodingDraftResponse> suggestCodes(@RequestBody MedicalCodingRequest request) {
        return ResponseEntity.status(201).body(codingService.suggestMedicalCodes(request));
    }

    @PostMapping(value = "/automate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Automate ICD-10-CM/CPT coding from notes, encounter summaries, and FHIR bundles with billing compliance validation and Safe-Harbor PHI redaction")
    public ResponseEntity<ClinicalCodingResult> automateCoding(@RequestBody ClinicalCodingRequest request) {
        return ResponseEntity.status(201).body(clinicalCodingEngine.automateClinicalCoding(request));
    }

    @PostMapping(value = "/compliance-check", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Run deterministic NCCI, medical necessity, anti-upcoding, and ICD-10 specificity compliance checks with Safe-Harbor PHI redaction")
    public ResponseEntity<ComplianceAuditResponse> checkBillingCompliance(@RequestBody ClinicalCodingRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("ClinicalCodingRequest is required.");
        }
        RedactedCodingContext ctx = clinicalCodingEngine.prepareRedactedCodingContext(request);
        return ResponseEntity.ok(new ComplianceAuditResponse(
                ctx.totalFhirResourcesProcessed(),
                ctx.preScreenComplianceReport(),
                ctx.redactedPayload().totalRedactions(),
                ctx.redactedPayload().redactionCountsByCategory()
        ));
    }
}
