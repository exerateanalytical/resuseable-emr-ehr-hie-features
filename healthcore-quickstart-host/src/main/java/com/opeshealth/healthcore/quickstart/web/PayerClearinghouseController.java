/*
 * Copyright (c) 2026 Opes Health Systems (https://www.opeshealthsystems.com)
 *
 * Author: Jude Nshome — Opes Health Systems
 * Email: nshome@opesware.com
 * Organization: Opes Health Systems, Douala, Cameroon
 *
 * Licensed under the Opes HealthCore Enterprise SDK License.
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.quickstart.service.PayerClearinghouseService;
import com.opeshealth.healthcore.quickstart.service.PayerClearinghouseService.ClaimAppealRequest;
import com.opeshealth.healthcore.quickstart.service.PayerClearinghouseService.ClaimSubmissionRequest;
import com.opeshealth.healthcore.quickstart.service.PayerClearinghouseService.ClearinghouseClaimRecord;
import com.opeshealth.healthcore.quickstart.service.PayerClearinghouseService.Edi835RemittanceRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * REST Controller for Payer Clearinghouse, EDI X12 ({@code 837P/837I}, {@code 835}),
 * Claim Scrubbing, FHIR {@code ExplanationOfBenefit}, and Denial Appeals.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/clearinghouse")
@Tag(name = "Payer Clearinghouse, EDI X12 (837 / 835) & Denial Appeals",
        description = "Pre-submission claim scrubbing, EDI X12 837P/837I generation, EDI 835 remittance reconciliation, FHIR ExplanationOfBenefit, and CARC/RARC denial appeal workflows")
public class PayerClearinghouseController {

    private final PayerClearinghouseService clearinghouseService;

    public PayerClearinghouseController(PayerClearinghouseService clearinghouseService) {
        this.clearinghouseService = Objects.requireNonNull(clearinghouseService, "PayerClearinghouseService is required");
    }

    @PostMapping(value = "/claims", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Scrub a healthcare claim and generate an EDI X12 837P/837I submission")
    public ResponseEntity<ClearinghouseClaimRecord> submitClaim(
            @RequestBody ClaimSubmissionRequest request) {
        return ResponseEntity.status(201).body(clearinghouseService.scrubAndSubmitClaim(request));
    }

    @PostMapping(value = "/remittances/835", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Reconcile an EDI X12 835 remittance and generate a FHIR ExplanationOfBenefit")
    public ResponseEntity<ClearinghouseClaimRecord> reconcileRemittance(
            @RequestBody Edi835RemittanceRequest remittance) {
        return ResponseEntity.ok(clearinghouseService.reconcileEdi835Remittance(remittance));
    }

    @PostMapping(value = "/claims/{claimId}/appeal", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "File a clinical denial appeal on a rejected or denied claim")
    public ResponseEntity<ClearinghouseClaimRecord> appealClaim(
            @PathVariable("claimId") UUID claimId,
            @RequestBody ClaimAppealRequest appealRequest) {
        return ResponseEntity.ok(clearinghouseService.fileDenialAppeal(claimId, appealRequest));
    }

    @GetMapping(value = "/claims", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List clearinghouse claims optionally filtered by status")
    public ResponseEntity<List<ClearinghouseClaimRecord>> listClaims(
            @RequestParam(value = "status", required = false) String status) {
        return ResponseEntity.ok(clearinghouseService.listClaimsByStatus(status));
    }
}
