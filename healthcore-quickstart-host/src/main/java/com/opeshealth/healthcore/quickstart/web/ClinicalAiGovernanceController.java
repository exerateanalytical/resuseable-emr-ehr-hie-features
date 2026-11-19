/*
 * =============================================================================
 * Opes HealthCore SDK — Clinical AI Governance, PHI Redaction & Attestation API
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.AttestationCommand;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.ClinicalAiDraftRecord;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.DraftAttestationStatus;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.TenantAiGovernanceMetrics;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway.RedactedClinicalPayload;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway.RedactionContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * REST Controller for Capability 10: PHI De-Identification Gateway, AI Governance &amp;
 * Mandatory Clinician Attestation Ledger.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/ai/governance")
@Tag(name = "Clinical AI — PHI Redaction Gateway, Governance & Attestation Ledger",
        description = "HIPAA Safe-Harbor 18-identifier redaction inspection, mandatory clinician sign-off on AI drafts, tenant AI kill-switch, and audit metrics")
public class ClinicalAiGovernanceController {

    public record PhiRedactionPreviewRequest(
            String rawClinicalText,
            List<String> knownPatientNames,
            List<String> knownIdentifiers,
            String knownBirthDate
    ) {}

    public record PhiRedactionPreviewResponse(
            String redactedText,
            Map<String, Integer> redactionCountsByCategory,
            int totalRedactions,
            String redactedSha256Hex
    ) {}

    public record TenantAiPolicyUpdateRequest(boolean aiEnabled) {}

    private final PhiRedactionGateway phiRedactionGateway;
    private final ClinicalAiGovernanceService governanceService;

    public ClinicalAiGovernanceController(
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService governanceService) {
        this.phiRedactionGateway = Objects.requireNonNull(phiRedactionGateway);
        this.governanceService = Objects.requireNonNull(governanceService);
    }

    @PostMapping(value = "/redact-preview", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Preview HIPAA Safe-Harbor 18-identifier redaction without invoking any external model")
    public ResponseEntity<PhiRedactionPreviewResponse> previewRedaction(@RequestBody PhiRedactionPreviewRequest req) {
        RedactionContext ctx = new RedactionContext(
                req != null && req.knownPatientNames() != null ? req.knownPatientNames() : List.of(),
                req != null && req.knownIdentifiers() != null ? req.knownIdentifiers() : List.of(),
                req != null ? req.knownBirthDate() : null
        );
        RedactedClinicalPayload payload = phiRedactionGateway.redact(req != null ? req.rawClinicalText() : "", ctx);
        return ResponseEntity.ok(new PhiRedactionPreviewResponse(
                payload.redactedText(),
                payload.redactionCountsByCategory(),
                payload.totalRedactions(),
                payload.redactedSha256Hex()
        ));
    }

    @GetMapping(value = "/drafts", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List Clinical AI drafts filtered by facility tenant and attestation status")
    public ResponseEntity<List<ClinicalAiDraftRecord>> listDrafts(
            @RequestParam(name = "facilityId", required = false) UUID facilityId,
            @RequestParam(name = "status", required = false) DraftAttestationStatus status) {
        return ResponseEntity.ok(governanceService.listDrafts(facilityId, status));
    }

    @GetMapping(value = "/drafts/{draftId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Retrieve a specific Clinical AI draft and its SHA-256 provenance record")
    public ResponseEntity<ClinicalAiDraftRecord> getDraft(@PathVariable("draftId") UUID draftId) {
        return ResponseEntity.ok(governanceService.getDraft(draftId));
    }

    @PostMapping(value = "/drafts/{draftId}/attest", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Perform mandatory clinician sign-off (APPROVED, APPROVED_WITH_EDITS, or REJECTED) on an AI draft")
    public ResponseEntity<ClinicalAiDraftRecord> attestDraft(
            @PathVariable("draftId") UUID draftId,
            @RequestBody AttestationCommand command) {
        return ResponseEntity.ok(governanceService.attestDraft(draftId, command));
    }

    @PutMapping(value = "/tenants/{facilityId}/policy", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Enable or disable Clinical AI capabilities for a specific facility tenant (kill-switch)")
    public ResponseEntity<Map<String, Object>> updateTenantPolicy(
            @PathVariable("facilityId") UUID facilityId,
            @RequestBody TenantAiPolicyUpdateRequest request) {
        boolean enabled = governanceService.setTenantAiEnabled(facilityId, request != null && request.aiEnabled());
        return ResponseEntity.ok(Map.of(
                "facilityId", facilityId,
                "aiEnabled", enabled
        ));
    }

    @GetMapping(value = "/tenants/{facilityId}/metrics", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Retrieve per-facility Clinical AI governance metrics, redaction totals, and attestation rates")
    public ResponseEntity<TenantAiGovernanceMetrics> getTenantMetrics(@PathVariable("facilityId") UUID facilityId) {
        return ResponseEntity.ok(governanceService.getTenantMetrics(facilityId));
    }
}
