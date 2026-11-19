/*
 * =============================================================================
 * Opes HealthCore SDK — ClinicalCodingEngine (ICD-10 / CPT + Billing Compliance)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.ai.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.TextGenerationResult;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.AiCapabilityType;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.ClinicalAiDraftRecord;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway.RedactedClinicalPayload;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway.RedactionContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reusable Spring component inside {@code healthcore-starter-clinical-ai} that automates
 * ICD-10-CM and CPT/HCPCS coding from clinical notes, encounter summaries, and HL7 FHIR R4
 * encounter bundles while enforcing billing compliance guardrails and mandatory HIPAA Safe-Harbor
 * PHI redaction.
 *
 * <p><strong>Compliance, Privacy &amp; Governance Guarantees:</strong></p>
 * <ul>
 *   <li><strong>Mandatory HIPAA Safe-Harbor Redaction:</strong> Harvests direct identifiers from
 *       optional FHIR {@code Patient} / {@code RelatedPerson} payloads plus caller-supplied patient
 *       identifiers, strips PII blocks, and scrubs all 18 Safe-Harbor identifier classes across
 *       clinical notes and encounter summaries before invoking Gemini.</li>
 *   <li><strong>Regulatory Billing Compliance Engine:</strong> Deterministically audits ICD-10-CM
 *       syntax &amp; specificity, principal diagnosis sequencing (external cause codes), CPT/HCPCS
 *       format, NCCI unbundling/mutually-exclusive E/M pairs, medical necessity diagnosis linkage,
 *       anti-upcoding Level 5 E/M documentation checks (False Claims Act / OIG), and telehealth
 *       modifiers.</li>
 *   <li><strong>Human-in-the-Loop Coder Sign-Off:</strong> Quarantines every coding bundle in
 *       {@link ClinicalAiGovernanceService} under {@code DRAFT_PENDING_ATTESTATION} so a certified
 *       medical coder or clinician attests before claim submission, and propagates upstream errors
 *       cleanly without fabricated billing codes.</li>
 * </ul>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Component
public class ClinicalCodingEngine {

    public enum CareSetting {
        INPATIENT,
        OUTPATIENT,
        EMERGENCY,
        AMBULATORY_SURGERY,
        TELEHEALTH
    }

    public enum BillingRegime {
        CMS_MEDICARE_USA,
        UHC_CBHI_AFRICA,
        EU_DRG,
        PRIVATE_COMMERCIAL
    }

    public enum ComplianceSeverity {
        BLOCKING_ERROR,
        WARNING,
        CDI_QUERY
    }

    public record CandidateBillingCode(
            String codeSystem,
            String code,
            String display,
            boolean primaryDiagnosis,
            List<String> modifiers
    ) {}

    public record ClinicalCodingRequest(
            UUID facilityId,
            UUID patientId,
            UUID encounterId,
            String coderOrClinicianId,
            CareSetting careSetting,
            BillingRegime billingRegime,
            String clinicalNoteText,
            String encounterSummaryText,
            List<String> encounterFhirResourcesJson,
            List<String> requestedCodeSystems,
            List<CandidateBillingCode> preCodedCandidates,
            List<String> knownPatientNames,
            List<String> knownIdentifiers,
            String knownDateOfBirth
    ) {}

    public record BillingComplianceFinding(
            String ruleCode,
            ComplianceSeverity severity,
            String affectedCode,
            String regulatoryBasis,
            String findingDetail,
            String remediationAction
    ) {}

    public record BillingComplianceReport(
            boolean claimReadyWithoutBlockingErrors,
            int blockingErrorCount,
            int warningCount,
            int cdiQueryCount,
            List<BillingComplianceFinding> findings
    ) {}

    public record RedactedCodingContext(
            RedactedClinicalPayload redactedPayload,
            Map<String, Integer> fhirResourceCountsByType,
            BillingComplianceReport preScreenComplianceReport,
            int totalFhirResourcesProcessed
    ) {}

    public record ClinicalCodingResult(
            UUID draftId,
            String attestationStatus,
            CareSetting careSetting,
            BillingRegime billingRegime,
            String modelId,
            Map<String, Integer> processedFhirResourceCounts,
            JsonNode automatedCodesAndEvidence,
            BillingComplianceReport billingComplianceReport,
            int totalPhiRedactions,
            Map<String, Integer> redactionCountsByCategory,
            long latencyMs
    ) {}

    private static final Pattern ICD10_CM_PATTERN = Pattern.compile("^[A-TV-Z][0-9][0-9AB](?:\\.[0-9A-TV-Z]{1,4})?$");
    private static final Pattern CPT_HCPCS_PATTERN = Pattern.compile("^(?:\\d{5}|[A-V]\\d{4})$");
    private static final Set<String> OUTPATIENT_EM_CODES = Set.of(
            "99202", "99203", "99204", "99205", "99211", "99212", "99213", "99214", "99215"
    );
    private static final Set<String> ED_EM_CODES = Set.of("99281", "99282", "99283", "99284", "99285");
    private static final Set<String> HIGH_LEVEL_EM_CODES = Set.of("99205", "99215", "99223", "99285");
    private static final Set<String> HIGH_COMPLEXITY_EVIDENCE_TOKENS = Set.of(
            "high complexity", "critical care", "life-threatening", "total time", "minutes spent",
            "severe exacerbation", "sepsis", "respiratory failure", "shock", "hospitalization", "parenteral"
    );

    private final GeminiClinicalApiClient geminiClient;
    private final PhiRedactionGateway phiRedactionGateway;
    private final ClinicalAiGovernanceService governanceService;
    private final ObjectMapper objectMapper;

    public ClinicalCodingEngine(
            GeminiClinicalApiClient geminiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService governanceService) {
        this.geminiClient = Objects.requireNonNull(geminiClient, "GeminiClinicalApiClient is required");
        this.phiRedactionGateway = Objects.requireNonNull(phiRedactionGateway, "PhiRedactionGateway is required");
        this.governanceService = Objects.requireNonNull(governanceService, "ClinicalAiGovernanceService is required");
        this.objectMapper = geminiClient.getObjectMapper();
    }

    /**
     * Automates ICD-10-CM and CPT/HCPCS extraction from clinical notes, encounter summaries, and
     * optional HL7 FHIR R4 encounter bundles with mandatory HIPAA Safe-Harbor PHI redaction,
     * Gemini structured coding ({@code gemini-4-hydrogen}), post-extraction billing compliance
     * validation, and {@code DRAFT_PENDING_ATTESTATION} quarantine.
     */
    public ClinicalCodingResult automateClinicalCoding(ClinicalCodingRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("ClinicalCodingRequest is required.");
        }
        governanceService.assertTenantAiAllowed(request.facilityId(), AiCapabilityType.AUTOMATED_MEDICAL_CODING);

        CareSetting setting = request.careSetting() != null ? request.careSetting() : CareSetting.OUTPATIENT;
        BillingRegime regime = request.billingRegime() != null ? request.billingRegime() : BillingRegime.CMS_MEDICARE_USA;
        List<String> codeSystems = (request.requestedCodeSystems() == null || request.requestedCodeSystems().isEmpty())
                ? List.of("ICD-10-CM", "CPT", "HCPCS")
                : request.requestedCodeSystems();

        RedactedCodingContext context = prepareRedactedCodingContext(request);
        if (context.redactedPayload().redactedText().isBlank()) {
            throw new IllegalArgumentException(
                    "At least one of clinicalNoteText, encounterSummaryText, or encounterFhirResourcesJson is required.");
        }

        String systemInstruction = """
                You are a certified professional medical coder (CPC / CCS) and Clinical Documentation
                Integrity (CDI) specialist embedded inside an HL7 FHIR R4 EMR and claims engine.
                Extract compliant ICD-10-CM diagnosis codes and CPT/HCPCS procedure/E&M codes strictly
                supported by the de-identified clinical documentation and encounter summary.
                Regulatory Rules:
                1. Never upcode or infer unconfirmed rule-out diagnoses in outpatient/ED settings.
                2. Select the highest billable specificity supported by documentation; if laterality,
                   episode of care, or acuity is missing, generate an explicit CDI query instead of guessing.
                3. Never sequence external cause codes (V00-Y99) as the primary diagnosis.
                4. Link every CPT/HCPCS procedure to supporting ICD-10-CM diagnosis codes for medical necessity,
                   and avoid NCCI unbundled panel/E&M pairs.
                5. Quote exact supporting evidence spans from the de-identified text and preserve all
                   Safe-Harbor placeholders verbatim. Output strictly valid JSON matching responseSchema.
                """;

        String prompt = "Care Setting: " + setting.name()
                + "\nBilling Regime: " + regime.name()
                + "\nTarget Code Systems: " + String.join(", ", codeSystems)
                + "\n\nDe-identified Clinical Note & Encounter Digest:\n"
                + context.redactedPayload().redactedText();

        TextGenerationResult aiResult = geminiClient.generateClinicalContent(
                systemInstruction,
                prompt,
                List.of(),
                buildAutomatedCodingSchema(),
                "MEDIUM"
        );

        JsonNode parsedCodes = parseJsonSafely(aiResult.text());
        List<CandidateBillingCode> extractedCandidates = extractCandidateCodesFromAiJson(parsedCodes);
        BillingComplianceReport complianceReport = validateBillingCompliance(
                setting, regime, context.redactedPayload().redactedText(), extractedCandidates);

        ClinicalAiDraftRecord draft = governanceService.registerDraft(
                request.facilityId(),
                request.patientId(),
                AiCapabilityType.AUTOMATED_MEDICAL_CODING,
                aiResult.modelId(),
                context.redactedPayload().redactedSha256Hex(),
                context.redactedPayload().redactionCountsByCategory(),
                parsedCodes.toString(),
                aiResult.tokenUsage(),
                aiResult.latencyMs(),
                request.coderOrClinicianId()
        );

        return new ClinicalCodingResult(
                draft.draftId(),
                draft.status().name(),
                setting,
                regime,
                aiResult.modelId(),
                context.fhirResourceCountsByType(),
                parsedCodes,
                complianceReport,
                context.redactedPayload().totalRedactions(),
                context.redactedPayload().redactionCountsByCategory(),
                aiResult.latencyMs()
        );
    }

    /**
     * Combines clinical notes, encounter summaries, and unwrapped HL7 FHIR R4 encounter resources,
     * harvests all direct patient identifiers, strips PII blocks, runs mandatory HIPAA Safe-Harbor
     * redaction, and performs deterministic pre-screen billing compliance checks on any
     * caller-supplied candidate codes.
     */
    public RedactedCodingContext prepareRedactedCodingContext(ClinicalCodingRequest request) {
        List<JsonNode> fhirNodes = new ArrayList<>();
        List<String> supplementalTexts = new ArrayList<>();
        parseFhirInputList(request.encounterFhirResourcesJson(), fhirNodes, supplementalTexts);

        Set<String> harvestedNames = new LinkedHashSet<>();
        Set<String> harvestedIds = new LinkedHashSet<>();
        if (request.knownPatientNames() != null) {
            harvestedNames.addAll(request.knownPatientNames());
        }
        if (request.knownIdentifiers() != null) {
            harvestedIds.addAll(request.knownIdentifiers());
        }
        String harvestedDob = (request.knownDateOfBirth() != null && !request.knownDateOfBirth().isBlank())
                ? request.knownDateOfBirth().trim() : null;

        Map<String, Integer> countsByType = new LinkedHashMap<>();
        for (JsonNode node : fhirNodes) {
            String type = node.path("resourceType").asText("UnknownResource");
            countsByType.merge(type, 1, Integer::sum);
            if ("Patient".equals(type) || "RelatedPerson".equals(type)) {
                harvestPatientIdentifiers(node, harvestedNames, harvestedIds);
                if (harvestedDob == null && node.hasNonNull("birthDate")) {
                    String dob = node.path("birthDate").asText("").trim();
                    if (!dob.isBlank()) {
                        harvestedDob = dob;
                    }
                }
            }
        }

        StringBuilder digest = new StringBuilder();
        if (request.clinicalNoteText() != null && !request.clinicalNoteText().isBlank()) {
            digest.append("=== CLINICAL NOTE ===\n")
                    .append(request.clinicalNoteText().trim()).append("\n");
        }
        if (request.encounterSummaryText() != null && !request.encounterSummaryText().isBlank()) {
            digest.append("=== ENCOUNTER SUMMARY ===\n")
                    .append(request.encounterSummaryText().trim()).append("\n");
        }
        if (!fhirNodes.isEmpty()) {
            digest.append("=== STRUCTURED FHIR ENCOUNTER CONTEXT ===\n");
            for (JsonNode node : fhirNodes) {
                digest.append(formatSafeFhirLine(node)).append("\n");
            }
        }
        for (String extra : supplementalTexts) {
            digest.append("[Supplemental Clinical Document] ").append(extra).append("\n");
        }

        RedactionContext redactionContext = new RedactionContext(
                List.copyOf(harvestedNames),
                List.copyOf(harvestedIds),
                harvestedDob
        );
        RedactedClinicalPayload redacted = phiRedactionGateway.redact(digest.toString().trim(), redactionContext);

        CareSetting setting = request.careSetting() != null ? request.careSetting() : CareSetting.OUTPATIENT;
        BillingRegime regime = request.billingRegime() != null ? request.billingRegime() : BillingRegime.CMS_MEDICARE_USA;
        BillingComplianceReport preScreen = validateBillingCompliance(
                setting, regime, redacted.redactedText(), request.preCodedCandidates());

        return new RedactedCodingContext(
                redacted,
                Collections.unmodifiableMap(countsByType),
                preScreen,
                fhirNodes.size()
        );
    }

    /**
     * Runs deterministic billing regulation &amp; NCCI compliance validation across candidate
     * ICD-10-CM and CPT/HCPCS codes against the de-identified clinical documentation.
     */
    public BillingComplianceReport validateBillingCompliance(
            CareSetting careSetting,
            BillingRegime billingRegime,
            String redactedClinicalText,
            List<CandidateBillingCode> candidateCodes) {
        List<BillingComplianceFinding> findings = new ArrayList<>();
        if (candidateCodes == null || candidateCodes.isEmpty()) {
            return new BillingComplianceReport(true, 0, 0, 0, List.of());
        }

        String docLow = redactedClinicalText != null ? redactedClinicalText.toLowerCase(Locale.ROOT) : "";
        List<CandidateBillingCode> icd10Codes = new ArrayList<>();
        List<CandidateBillingCode> cptCodes = new ArrayList<>();

        for (CandidateBillingCode c : candidateCodes) {
            if (c == null || c.code() == null || c.code().isBlank()) {
                continue;
            }
            String sys = c.codeSystem() != null ? c.codeSystem().toUpperCase(Locale.ROOT) : "";
            String code = c.code().trim().toUpperCase(Locale.ROOT);
            if (sys.contains("ICD-10") || sys.contains("ICD10")) {
                icd10Codes.add(c);
                validateSingleIcd10(code, c.primaryDiagnosis(), findings);
            } else if (sys.contains("CPT") || sys.contains("HCPCS")) {
                cptCodes.add(c);
                validateSingleCpt(code, careSetting, c.modifiers(), docLow, findings);
            }
        }

        // Medical necessity check: CPT/HCPCS procedures require at least one supporting ICD-10 diagnosis
        if (!cptCodes.isEmpty() && icd10Codes.isEmpty()) {
            findings.add(new BillingComplianceFinding(
                    "BILL-NECESSITY-001",
                    ComplianceSeverity.BLOCKING_ERROR,
                    cptCodes.get(0).code(),
                    "CMS Medical Necessity / Claim Adjudication Rule",
                    "CPT/HCPCS procedure code(s) present without any linked ICD-10-CM diagnosis code.",
                    "Attach at least one documented ICD-10-CM diagnosis justifying medical necessity before submission."
            ));
        }

        // NCCI Mutually Exclusive E/M Check
        List<String> matchedOutpatientEm = cptCodes.stream()
                .map(c -> c.code().trim().toUpperCase(Locale.ROOT))
                .filter(OUTPATIENT_EM_CODES::contains)
                .distinct()
                .toList();
        if (matchedOutpatientEm.size() >= 2) {
            findings.add(new BillingComplianceFinding(
                    "BILL-NCCI-001",
                    ComplianceSeverity.BLOCKING_ERROR,
                    String.join("+", matchedOutpatientEm),
                    "CMS NCCI Mutually Exclusive E/M Policy",
                    "Multiple outpatient E/M visit codes (" + String.join(", ", matchedOutpatientEm)
                            + ") billed for the same encounter.",
                    "Retain a single E/M level reflecting documented MDM/time or verify distinct split/modifier justification."
            ));
        }

        List<String> matchedEdEm = cptCodes.stream()
                .map(c -> c.code().trim().toUpperCase(Locale.ROOT))
                .filter(ED_EM_CODES::contains)
                .distinct()
                .toList();
        if (matchedEdEm.size() >= 2) {
            findings.add(new BillingComplianceFinding(
                    "BILL-NCCI-001",
                    ComplianceSeverity.BLOCKING_ERROR,
                    String.join("+", matchedEdEm),
                    "CMS NCCI Mutually Exclusive Emergency E/M Policy",
                    "Multiple ED E/M codes (" + String.join(", ", matchedEdEm) + ") billed on one encounter.",
                    "Report only one ED E/M code corresponding to highest documented visit complexity."
            ));
        }

        // NCCI Laboratory Panel Unbundling Check (CMP 80053 + BMP 80048)
        Set<String> cptSet = new LinkedHashSet<>();
        for (CandidateBillingCode c : cptCodes) {
            cptSet.add(c.code().trim().toUpperCase(Locale.ROOT));
        }
        if (cptSet.contains("80053") && cptSet.contains("80048")) {
            findings.add(new BillingComplianceFinding(
                    "BILL-NCCI-002",
                    ComplianceSeverity.BLOCKING_ERROR,
                    "80053+80048",
                    "CMS NCCI Procedure-to-Procedure (PTP) Unbundling Edit",
                    "Basic Metabolic Panel (80048) is bundled into Comprehensive Metabolic Panel (80053).",
                    "Remove duplicate BMP (80048) unless performed at a distinct documented specimen time with modifier 59/XE."
            ));
        }

        int blocking = (int) findings.stream().filter(f -> f.severity() == ComplianceSeverity.BLOCKING_ERROR).count();
        int warnings = (int) findings.stream().filter(f -> f.severity() == ComplianceSeverity.WARNING).count();
        int queries = (int) findings.stream().filter(f -> f.severity() == ComplianceSeverity.CDI_QUERY).count();

        return new BillingComplianceReport(
                blocking == 0,
                blocking,
                warnings,
                queries,
                Collections.unmodifiableList(findings)
        );
    }

    private void validateSingleIcd10(String code, boolean primaryDiagnosis, List<BillingComplianceFinding> findings) {
        if (!ICD10_CM_PATTERN.matcher(code).matches()) {
            findings.add(new BillingComplianceFinding(
                    "BILL-ICD10-FORMAT",
                    ComplianceSeverity.BLOCKING_ERROR,
                    code,
                    "ICD-10-CM Official Code Structure",
                    "Code '" + code + "' does not conform to valid ICD-10-CM syntax.",
                    "Replace with a valid billable ICD-10-CM code from the current annual code set."
            ));
            return;
        }
        char first = code.charAt(0);
        if (primaryDiagnosis && (first == 'V' || first == 'W' || first == 'X' || first == 'Y')) {
            findings.add(new BillingComplianceFinding(
                    "BILL-ICD10-SEQ",
                    ComplianceSeverity.BLOCKING_ERROR,
                    code,
                    "ICD-10-CM Official Guidelines Section I.C.20 (External Cause Sequencing)",
                    "External cause code '" + code + "' cannot be sequenced as the principal/primary diagnosis.",
                    "Sequence the underlying injury or clinical condition as primary and move '" + code + "' to a secondary position."
            ));
        }
        if (code.endsWith(".9")) {
            findings.add(new BillingComplianceFinding(
                    "BILL-ICD10-UNSPECIFIED",
                    ComplianceSeverity.CDI_QUERY,
                    code,
                    "ICD-10-CM Specificity & Payer Audit Readiness",
                    "Unspecified diagnosis code '" + code + "' may trigger payer specificity review or lower DRG accuracy.",
                    "Verify whether clinical documentation supports a more specific laterality, etiology, or complication code."
            ));
        }
    }

    private void validateSingleCpt(
            String code,
            CareSetting careSetting,
            List<String> modifiers,
            String docLow,
            List<BillingComplianceFinding> findings) {
        if (!CPT_HCPCS_PATTERN.matcher(code).matches()) {
            findings.add(new BillingComplianceFinding(
                    "BILL-CPT-FORMAT",
                    ComplianceSeverity.BLOCKING_ERROR,
                    code,
                    "AMA CPT / CMS HCPCS Level II Format",
                    "Procedure code '" + code + "' is not a valid 5-character CPT or HCPCS Level II code.",
                    "Verify and correct the procedure code against current AMA CPT / HCPCS tables."
            ));
            return;
        }
        if (HIGH_LEVEL_EM_CODES.contains(code)) {
            boolean hasHighComplexityCue = HIGH_COMPLEXITY_EVIDENCE_TOKENS.stream().anyMatch(docLow::contains);
            if (!hasHighComplexityCue) {
                findings.add(new BillingComplianceFinding(
                        "BILL-UPCODING-001",
                        ComplianceSeverity.WARNING,
                        code,
                        "OIG Compliance / False Claims Act Anti-Upcoding Guardrail",
                        "High-complexity Level 5 E/M code '" + code
                                + "' lacks explicit high-MDM or total-time supporting documentation in the note.",
                        "Confirm high medical decision-making (acute threat to life/bodily function) or total time statement before attesting."
                ));
            }
        }
        if (careSetting == CareSetting.TELEHEALTH && OUTPATIENT_EM_CODES.contains(code)) {
            boolean hasTelehealthMod = modifiers != null && modifiers.stream()
                    .filter(Objects::nonNull)
                    .map(m -> m.trim().toUpperCase(Locale.ROOT))
                    .anyMatch(m -> "95".equals(m) || "GT".equals(m) || "93".equals(m));
            if (!hasTelehealthMod) {
                findings.add(new BillingComplianceFinding(
                        "BILL-TELEHEALTH-MOD",
                        ComplianceSeverity.WARNING,
                        code,
                        "CMS / Commercial Telehealth Billing Policy",
                        "Telehealth E/M code '" + code + "' is missing a synchronous telehealth modifier (95 / GT / 93).",
                        "Append modifier 95 (or payer-required GT/93) and verify Place of Service (POS 02/10)."
                ));
            }
        }
    }

    private List<CandidateBillingCode> extractCandidateCodesFromAiJson(JsonNode root) {
        List<CandidateBillingCode> candidates = new ArrayList<>();
        if (root == null || !root.isObject()) {
            return candidates;
        }
        JsonNode icdArray = root.path("icd10Diagnoses");
        if (icdArray.isArray()) {
            for (JsonNode n : icdArray) {
                String code = n.path("code").asText("").trim();
                if (!code.isBlank()) {
                    candidates.add(new CandidateBillingCode(
                            "ICD-10-CM",
                            code,
                            n.path("display").asText(""),
                            n.path("primaryDiagnosis").asBoolean(false),
                            List.of()
                    ));
                }
            }
        }
        JsonNode cptArray = root.path("cptProcedures");
        if (cptArray.isArray()) {
            for (JsonNode n : cptArray) {
                String code = n.path("code").asText("").trim();
                if (!code.isBlank()) {
                    List<String> mods = new ArrayList<>();
                    if (n.path("suggestedModifiers").isArray()) {
                        for (JsonNode m : n.path("suggestedModifiers")) {
                            String mv = m.asText("").trim();
                            if (!mv.isBlank()) {
                                mods.add(mv);
                            }
                        }
                    }
                    candidates.add(new CandidateBillingCode(
                            "CPT",
                            code,
                            n.path("display").asText(""),
                            false,
                            mods
                    ));
                }
            }
        }
        return candidates;
    }

    private void parseFhirInputList(List<String> rawList, List<JsonNode> nodeSink, List<String> textSink) {
        if (rawList == null) {
            return;
        }
        for (String raw : rawList) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                JsonNode parsed = objectMapper.readTree(raw);
                flattenFhirNode(parsed, nodeSink);
            } catch (Exception ex) {
                textSink.add(raw.trim());
            }
        }
    }

    private void flattenFhirNode(JsonNode node, List<JsonNode> sink) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                flattenFhirNode(item, sink);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        if ("Bundle".equals(node.path("resourceType").asText("")) && node.path("entry").isArray()) {
            for (JsonNode entry : node.path("entry")) {
                if (entry.path("resource").isObject()) {
                    flattenFhirNode(entry.path("resource"), sink);
                }
            }
            return;
        }
        sink.add(node);
    }

    private void harvestPatientIdentifiers(JsonNode patientNode, Set<String> names, Set<String> ids) {
        if (patientNode.path("name").isArray()) {
            for (JsonNode hn : patientNode.path("name")) {
                String text = hn.path("text").asText("").trim();
                if (text.length() >= 2) {
                    names.add(text);
                }
                String family = hn.path("family").asText("").trim();
                List<String> givens = new ArrayList<>();
                if (hn.path("given").isArray()) {
                    for (JsonNode g : hn.path("given")) {
                        String gv = g.asText("").trim();
                        if (gv.length() >= 2) {
                            givens.add(gv);
                            names.add(gv);
                        }
                    }
                }
                if (family.length() >= 2) {
                    names.add(family);
                    if (!givens.isEmpty()) {
                        names.add(String.join(" ", givens) + " " + family);
                    }
                }
            }
        }
        if (patientNode.path("identifier").isArray()) {
            for (JsonNode id : patientNode.path("identifier")) {
                String v = id.path("value").asText("").trim();
                if (v.length() >= 3) {
                    ids.add(v);
                }
            }
        }
        if (patientNode.path("telecom").isArray()) {
            for (JsonNode tel : patientNode.path("telecom")) {
                String v = tel.path("value").asText("").trim();
                if (v.length() >= 4) {
                    ids.add(v);
                }
            }
        }
        if (patientNode.path("address").isArray()) {
            for (JsonNode addr : patientNode.path("address")) {
                if (addr.path("line").isArray()) {
                    for (JsonNode line : addr.path("line")) {
                        String lv = line.asText("").trim();
                        if (lv.length() >= 4) {
                            ids.add(lv);
                        }
                    }
                }
            }
        }
    }

    private String formatSafeFhirLine(JsonNode resource) {
        String type = resource.path("resourceType").asText("Resource");
        return switch (type) {
            case "Patient" -> {
                String dob = resource.path("birthDate").asText("");
                String birthYear = dob.length() >= 4 ? dob.substring(0, 4) : "unspecified";
                yield "[Patient] gender=" + resource.path("gender").asText("unknown") + ", birthYear=" + birthYear;
            }
            case "Encounter" -> "[Encounter] class=" + resource.path("class").path("code").asText("AMB")
                    + ", status=" + resource.path("status").asText("finished")
                    + ", reason=" + extractCodeableDisplay(resource.path("reasonCode").path(0));
            case "Condition" -> "[Condition] condition=" + extractCodeableDisplay(resource.path("code"))
                    + ", status=" + extractCodeableDisplay(resource.path("clinicalStatus"));
            case "Procedure" -> "[Procedure] procedure=" + extractCodeableDisplay(resource.path("code"))
                    + ", status=" + resource.path("status").asText("completed");
            case "Observation" -> "[Observation] code=" + extractCodeableDisplay(resource.path("code"))
                    + ", value=" + resource.path("valueQuantity").path("value").asText("")
                    + " " + resource.path("valueQuantity").path("unit").asText("");
            case "DiagnosticReport" -> "[DiagnosticReport] code=" + extractCodeableDisplay(resource.path("code"))
                    + ", conclusion=" + resource.path("conclusion").asText("N/A");
            default -> "[" + type + "] " + resource.toString();
        };
    }

    private String extractCodeableDisplay(JsonNode concept) {
        if (concept == null || concept.isMissingNode() || concept.isNull()) {
            return "unspecified";
        }
        String text = concept.path("text").asText("").trim();
        JsonNode coding = concept.path("coding").path(0);
        String code = coding.path("code").asText("").trim();
        String display = coding.path("display").asText("").trim();
        String label = !text.isBlank() ? text : (!display.isBlank() ? display : "unspecified");
        return !code.isBlank() ? label + " (" + code + ")" : label;
    }

    private ObjectNode buildAutomatedCodingSchema() {
        ObjectNode root = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode props = objectMapper.createObjectNode();

        ObjectNode icdItem = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode icdProps = objectMapper.createObjectNode();
        icdProps.set("code", objectMapper.createObjectNode().put("type", "STRING"));
        icdProps.set("display", objectMapper.createObjectNode().put("type", "STRING"));
        icdProps.set("primaryDiagnosis", objectMapper.createObjectNode().put("type", "BOOLEAN"));
        icdProps.set("poaIndicator", objectMapper.createObjectNode().put("type", "STRING"));
        icdProps.set("evidenceSpan", objectMapper.createObjectNode().put("type", "STRING"));
        icdProps.set("specificityComplete", objectMapper.createObjectNode().put("type", "BOOLEAN"));
        icdProps.set("confidenceScore", objectMapper.createObjectNode().put("type", "NUMBER"));
        icdItem.set("properties", icdProps);
        props.set("icd10Diagnoses", objectMapper.createObjectNode().put("type", "ARRAY").set("items", icdItem));

        ObjectNode cptItem = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode cptProps = objectMapper.createObjectNode();
        cptProps.set("code", objectMapper.createObjectNode().put("type", "STRING"));
        cptProps.set("display", objectMapper.createObjectNode().put("type", "STRING"));
        cptProps.set("suggestedModifiers", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        cptProps.set("units", objectMapper.createObjectNode().put("type", "INTEGER"));
        cptProps.set("linkedDiagnosisCodes", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        cptProps.set("evidenceSpan", objectMapper.createObjectNode().put("type", "STRING"));
        cptProps.set("confidenceScore", objectMapper.createObjectNode().put("type", "NUMBER"));
        cptItem.set("properties", cptProps);
        props.set("cptProcedures", objectMapper.createObjectNode().put("type", "ARRAY").set("items", cptItem));

        ObjectNode suppItem = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode suppProps = objectMapper.createObjectNode();
        suppProps.set("codeSystem", objectMapper.createObjectNode().put("type", "STRING"));
        suppProps.set("code", objectMapper.createObjectNode().put("type", "STRING"));
        suppProps.set("display", objectMapper.createObjectNode().put("type", "STRING"));
        suppProps.set("evidenceSpan", objectMapper.createObjectNode().put("type", "STRING"));
        suppItem.set("properties", suppProps);
        props.set("supplementaryCodes", objectMapper.createObjectNode().put("type", "ARRAY").set("items", suppItem));

        props.set("suggestedDrgOrUhcTariffGroup", objectMapper.createObjectNode().put("type", "STRING"));
        props.set("clinicalDocumentationQueries", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        props.set("billingComplianceAttestationNotes", objectMapper.createObjectNode().put("type", "STRING"));

        root.set("properties", props);
        ArrayNode req = objectMapper.createArrayNode()
                .add("icd10Diagnoses")
                .add("cptProcedures")
                .add("suggestedDrgOrUhcTariffGroup")
                .add("billingComplianceAttestationNotes");
        root.set("required", req);
        return root;
    }

    private JsonNode parseJsonSafely(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (Exception ex) {
            return objectMapper.createObjectNode().put("rawOutput", text);
        }
    }
}
