/*
 * =============================================================================
 * Opes HealthCore SDK — Evidence-Based CDSS Engine & CDS Hooks 2.0 Service
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.terminology.cdss;

import com.opeshealth.healthcore.terminology.domain.TerminologyEntities.DdiRuleEntity;
import com.opeshealth.healthcore.terminology.service.TerminologyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Deterministic Clinical Decision Support System (CDSS) and HL7 CDS Hooks 2.0 gateway.
 *
 * <p>Evaluates 5 core clinical safety checks in <10 ms with full offline parity on KMP Mobile:
 * Drug-Allergy class subsumption, Drug-Drug Interactions, Drug-Condition/Renal Contraindications,
 * Pediatric weight-based mg/kg dosing ceiling, and NEWS2/Lab Panic critical values.</p>
 */
public class CdsHooksEngine {

    public record CdsCard(
            String uuid,
            String summary,
            String detail,
            String indicator, // info, warning, critical
            String sourceLabel,
            List<String> suggestions,
            boolean overrideRequired
    ) {}

    public record OrderSafetyRequest(
            UUID patientId,
            BigDecimal weightKg,
            BigDecimal eGfr,
            boolean pregnant,
            List<String> activeMedicationCodes,
            List<String> allergySubstanceCodes,
            List<String> conditionCodes,
            String proposedDrugCode,
            BigDecimal proposedSingleDoseMg
    ) {}

    public record VitalsPanicRequest(
            BigDecimal tempCelsius,
            Integer heartRateBpm,
            Integer respRatePerMin,
            Integer systolicBp,
            Integer spo2Percent,
            BigDecimal potassiumMmolL,
            BigDecimal glucoseMgDl
    ) {}

    private final TerminologyService terminologyService;
    private final TerminologyService.DdiRuleRepository ddiRepository;

    public CdsHooksEngine(
            TerminologyService terminologyService,
            TerminologyService.DdiRuleRepository ddiRepository) {
        this.terminologyService = terminologyService;
        this.ddiRepository = ddiRepository;
    }

    /** Evaluates CPOE / e-Prescribing order safety prior to signature. */
    public List<CdsCard> evaluateOrderSafety(OrderSafetyRequest req) {
        List<CdsCard> cards = new ArrayList<>();
        if (req.proposedDrugCode() == null) return cards;

        List<DdiRuleEntity> rules = ddiRepository.findByDrugACodeIn(List.of(req.proposedDrugCode()));

        for (DdiRuleEntity rule : rules) {
            if ("DDI".equals(rule.getRuleType())
                    && req.activeMedicationCodes() != null
                    && req.activeMedicationCodes().contains(rule.getDrugBOrConditionCode())) {
                cards.add(new CdsCard(UUID.randomUUID().toString(),
                        "Drug-Drug Interaction (" + rule.getSeverity() + ")",
                        rule.getClinicalMessage(),
                        rule.getSeverity().toLowerCase(Locale.ROOT),
                        "Opes HealthCore CDSS",
                        rule.getRecommendedAction() != null ? List.of(rule.getRecommendedAction()) : List.of(),
                        "CRITICAL".equals(rule.getSeverity())));
            }

            if ("DRUG_ALLERGY_CLASS".equals(rule.getRuleType()) && req.allergySubstanceCodes() != null) {
                for (String allergy : req.allergySubstanceCodes()) {
                    if (Objects.equals(allergy, rule.getDrugBOrConditionCode())
                            || terminologyService.isSubsumedBy(TerminologyService.SNOMED, allergy, req.proposedDrugCode())) {
                        cards.add(new CdsCard(UUID.randomUUID().toString(),
                                "Drug-Allergy Cross-Reactivity Alert",
                                rule.getClinicalMessage(),
                                "critical",
                                "Opes HealthCore CDSS",
                                List.of(rule.getRecommendedAction()),
                                true));
                    }
                }
            }

            if ("CONTRAINDICATION".equals(rule.getRuleType())
                    && ((req.conditionCodes() != null && req.conditionCodes().contains(rule.getDrugBOrConditionCode()))
                    || (req.eGfr() != null && req.eGfr().compareTo(new BigDecimal("30")) < 0 && "6809".equals(req.proposedDrugCode())))) {
                cards.add(new CdsCard(UUID.randomUUID().toString(),
                        "Renal / Condition Contraindication",
                        rule.getClinicalMessage(),
                        "critical",
                        "Opes HealthCore CDSS",
                        List.of(rule.getRecommendedAction()),
                        true));
            }
        }

        // Duplicate therapy check
        if (req.activeMedicationCodes() != null && req.activeMedicationCodes().contains(req.proposedDrugCode())) {
            cards.add(new CdsCard(UUID.randomUUID().toString(),
                    "Duplicate Therapy Detected",
                    "Patient already has an active prescription for code " + req.proposedDrugCode(),
                    "warning",
                    "Opes HealthCore CDSS",
                    List.of("Review active MAR before adding duplicate agent"),
                    false));
        }

        // Pediatric / weight-based paracetamol max 15 mg/kg/dose check
        if ("161".equals(req.proposedDrugCode()) && req.weightKg() != null && req.proposedSingleDoseMg() != null) {
            BigDecimal maxDose = req.weightKg().multiply(new BigDecimal("15")).setScale(1, RoundingMode.HALF_UP);
            if (req.proposedSingleDoseMg().compareTo(maxDose) > 0) {
                cards.add(new CdsCard(UUID.randomUUID().toString(),
                        "Weight-Based Dosing Ceiling Exceeded",
                        "Proposed " + req.proposedSingleDoseMg() + " mg exceeds 15 mg/kg ceiling (" + maxDose + " mg).",
                        "critical",
                        "Opes HealthCore Pediatric Dosing CDSS",
                        List.of("Adjust single dose to <= " + maxDose + " mg"),
                        true));
            }
        }
        return cards;
    }

    /** Evaluates NEWS2 vitals severity and critical laboratory panic thresholds. */
    public List<CdsCard> evaluateVitalsAndLabPanic(VitalsPanicRequest req) {
        List<CdsCard> cards = new ArrayList<>();
        int news2 = 0;
        if (req.spo2Percent() != null && req.spo2Percent() <= 91) news2 += 3;
        if (req.systolicBp() != null && req.systolicBp() <= 90) news2 += 3;
        if (req.heartRateBpm() != null && (req.heartRateBpm() >= 131 || req.heartRateBpm() <= 40)) news2 += 3;
        if (req.respRatePerMin() != null && (req.respRatePerMin() >= 25 || req.respRatePerMin() <= 8)) news2 += 3;
        if (req.tempCelsius() != null && req.tempCelsius().compareTo(new BigDecimal("39.1")) >= 0) news2 += 2;

        if (news2 >= 5) {
            cards.add(new CdsCard(UUID.randomUUID().toString(),
                    "NEWS2 High Clinical Risk Alert (Score=" + news2 + ")",
                    "Urgent clinician/rapid-response bedside review required immediately.",
                    news2 >= 7 ? "critical" : "warning",
                    "Opes HealthCore Early Warning Engine",
                    List.of("Escalate to attending physician", "Continuous vital sign monitoring"),
                    false));
        }

        if (req.potassiumMmolL() != null && (req.potassiumMmolL().compareTo(new BigDecimal("6.2")) >= 0
                || req.potassiumMmolL().compareTo(new BigDecimal("2.5")) <= 0)) {
            cards.add(new CdsCard(UUID.randomUUID().toString(),
                    "PANIC LAB: Critical Serum Potassium (" + req.potassiumMmolL() + " mmol/L)",
                    "Immediate ECG and electrolyte stabilization protocol indicated.",
                    "critical",
                    "Opes HealthCore LIS Panic CDSS",
                    List.of("Trigger critical value read-back log", "Perform 12-lead ECG"),
                    false));
        }
        return cards;
    }

    /** HL7 CDS Hooks 2.0 + Terminology Lookup REST Gateway. */
    @RestController
    @Tag(name = "Terminology & CDS Hooks 2.0", description = "LOINC/SNOMED/ICD-10 search, UCUM conversion, and CDS Hooks")
    public static class CdsHooksController {

        private final TerminologyService terminologyService;
        private final CdsHooksEngine cdssEngine;

        public CdsHooksController(TerminologyService terminologyService, CdsHooksEngine cdssEngine) {
            this.terminologyService = terminologyService;
            this.cdssEngine = cdssEngine;
        }

        @GetMapping("/cds-services")
        @Operation(summary = "CDS Hooks 2.0 Service Discovery")
        public Map<String, Object> discovery() {
            return Map.of("services", List.of(
                    Map.of("id", "opes-order-safety", "hook", "order-sign",
                            "title", "Opes HealthCore Medication & Allergy CDSS",
                            "description", "DDI, Drug-Allergy class, Renal contraindication, and Pediatric dosing checks"),
                    Map.of("id", "opes-vitals-panic", "hook", "patient-view",
                            "title", "Opes HealthCore NEWS2 & Lab Panic CDSS",
                            "description", "Early warning score and critical lab panic detection")
            ));
        }

        @PostMapping("/cds-services/opes-order-safety")
        @Operation(summary = "Invoke order-sign CDSS safety evaluation")
        public Map<String, Object> invokeOrderSafety(@RequestBody OrderSafetyRequest request) {
            return Map.of("cards", cdssEngine.evaluateOrderSafety(request));
        }

        @PostMapping("/cds-services/opes-vitals-panic")
        @Operation(summary = "Invoke patient-view NEWS2 & lab panic evaluation")
        public Map<String, Object> invokeVitalsPanic(@RequestBody VitalsPanicRequest request) {
            return Map.of("cards", cdssEngine.evaluateVitalsAndLabPanic(request));
        }

        @GetMapping("/api/v1/terminology/search")
        @Operation(summary = "Search coded clinical concepts (LOINC, SNOMED, ICD-10, RxNorm)")
        public List<?> searchConcepts(
                @RequestParam String systemUri,
                @RequestParam String q,
                @RequestParam(defaultValue = "20") int limit) {
            return terminologyService.search(systemUri, q, limit);
        }
    }
}
