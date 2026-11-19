/*
 * =============================================================================
 * Opes HealthCore SDK — Medication Reconciliation AI REST Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.ai.reconciliation.MedicationReconciliationEngine;
import com.opeshealth.healthcore.ai.reconciliation.MedicationReconciliationEngine.MedicationReconciliationRequest;
import com.opeshealth.healthcore.ai.reconciliation.MedicationReconciliationEngine.MedicationReconciliationResult;
import com.opeshealth.healthcore.ai.reconciliation.MedicationReconciliationEngine.RedactedReconciliationContext;
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
 * REST Controller exposing three-way medication reconciliation powered by
 * {@link MedicationReconciliationEngine}, comparing current EHR medication lists,
 * clinical admission notes, external pharmacy dispense records, and HL7 FHIR R4 payloads
 * with mandatory HIPAA Safe-Harbor PHI redaction and clinician review quarantine.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/ai/medication-reconciliation")
@Tag(name = "Clinical AI — Medication Reconciliation Engine",
        description = "Three-way medication reconciliation across current EHR medication lists, admission notes, and pharmacy records with Safe-Harbor PHI redaction and clinician attestation")
public class MedicationReconciliationAiController {

    private final MedicationReconciliationEngine reconciliationEngine;

    public MedicationReconciliationAiController(MedicationReconciliationEngine reconciliationEngine) {
        this.reconciliationEngine = Objects.requireNonNull(reconciliationEngine, "MedicationReconciliationEngine is required");
    }

    @PostMapping(value = "/reconcile", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Reconcile current medication list, admission notes, and pharmacy records with Safe-Harbor PHI redaction, deterministic discrepancy checks, and Gemini clinical synthesis")
    public ResponseEntity<MedicationReconciliationResult> reconcileMedications(
            @RequestBody MedicationReconciliationRequest request) {
        return ResponseEntity.status(201).body(reconciliationEngine.reconcileMedications(request));
    }

    @PostMapping(value = "/deterministic-check", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Run local deterministic three-way medication discrepancy detection and Safe-Harbor PHI redaction preview without invoking an external LLM")
    public ResponseEntity<RedactedReconciliationContext> runDeterministicCheck(
            @RequestBody MedicationReconciliationRequest request) {
        return ResponseEntity.ok(reconciliationEngine.prepareRedactedReconciliationContext(request));
    }
}
