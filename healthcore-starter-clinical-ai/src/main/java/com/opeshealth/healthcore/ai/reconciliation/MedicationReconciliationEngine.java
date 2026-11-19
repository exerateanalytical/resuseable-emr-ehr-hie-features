/*
 * =============================================================================
 * Opes HealthCore SDK — MedicationReconciliationEngine (3-Way Med Rec + PHI Safe-Harbor)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.ai.reconciliation;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reusable Spring component inside {@code healthcore-starter-clinical-ai} that performs
 * three-way medication reconciliation across a patient's current EHR medication list,
 * clinical admission notes, external pharmacy dispense records, and optional HL7 FHIR R4
 * medication/allergy resources to surface clinically significant discrepancies for
 * pharmacist and clinician review.
 *
 * <p><strong>Clinical Safety, Privacy &amp; Governance Guarantees:</strong></p>
 * <ul>
 *   <li><strong>Mandatory HIPAA Safe-Harbor Redaction:</strong> Harvests direct patient and
 *       related-person identifiers from optional FHIR payloads plus caller-supplied identifiers,
 *       strips PII blocks, and scrubs all 18 HIPAA Safe-Harbor classes across admission notes,
 *       medication lists, and pharmacy records before invoking Gemini.</li>
 *   <li><strong>Deterministic Three-Way Discrepancy Engine:</strong> Normalizes brand-to-generic
 *       drug names, doses, and dosing frequencies to deterministically detect unintentional
 *       omissions, unlisted pharmacy fills, unexplained commissions, dose/strength mismatches,
 *       frequency mismatches, therapeutic class duplications, allergy contraindications, and
 *       pharmacy fill/adherence gaps.</li>
 *   <li><strong>Human-in-the-Loop Clinician Review Gate:</strong> Quarantines every AI
 *       reconciliation draft in {@link ClinicalAiGovernanceService} under
 *       {@code DRAFT_PENDING_ATTESTATION} so a licensed clinician or pharmacist reviews and signs
 *       off before modifying active medication orders, and propagates upstream errors cleanly
 *       without fabricated reconciliation outputs.</li>
 * </ul>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Component
public class MedicationReconciliationEngine {

    public enum ReconciliationTransition {
        ADMISSION,
        TRANSFER,
        DISCHARGE,
        AMBULATORY_FOLLOWUP
    }

    public enum MedicationSource {
        CURRENT_MEDICATION_LIST,
        ADMISSION_NOTE,
        PHARMACY_RECORD,
        FHIR_MEDICATION_STATEMENT,
        FHIR_MEDICATION_REQUEST,
        FHIR_MEDICATION_DISPENSE
    }

    public enum DiscrepancyCategory {
        UNINTENTIONAL_OMISSION,
        UNINTENTIONAL_COMMISSION,
        DOSE_MISMATCH,
        FREQUENCY_MISMATCH,
        THERAPEUTIC_DUPLICATION,
        ADHERENCE_OR_FILL_GAP,
        ALLERGY_CONTRAINDICATION
    }

    public enum DiscrepancySeverity {
        CRITICAL_SAFETY_RISK,
        HIGH,
        MODERATE,
        LOW
    }

    public record MedicationEntry(
            String drugName,
            String rxNormCode,
            String dose,
            String unit,
            String frequency,
            String route,
            String status,
            MedicationSource source,
            String notes
    ) {}

    public record PharmacyDispenseRecord(
            String pharmacyName,
            String drugName,
            String rxNormCode,
            String strengthAndDose,
            String sigInstructions,
            String lastFillDate,
            Integer daysSupply,
            String dispenseStatus
    ) {}

    public record MedicationReconciliationRequest(
            UUID facilityId,
            UUID patientId,
            UUID encounterId,
            String clinicianId,
            ReconciliationTransition transitionType,
            List<MedicationEntry> currentMedicationList,
            String admissionNotesText,
            List<PharmacyDispenseRecord> pharmacyRecords,
            List<String> fhirResourcesJson,
            List<String> documentedAllergies,
            List<String> knownPatientNames,
            List<String> knownIdentifiers,
            String knownDateOfBirth
    ) {}

    public record ReconciliationDiscrepancy(
            String ruleCode,
            DiscrepancyCategory category,
            DiscrepancySeverity severity,
            String normalizedDrugName,
            String currentListDetail,
            String admissionNoteDetail,
            String pharmacyRecordDetail,
            String clinicalImpact,
            String recommendedClinicianAction
    ) {}

    public record DeterministicReconciliationBaseline(
            int currentListCount,
            int admissionNoteMedicationCount,
            int pharmacyRecordCount,
            int fhirMedicationCount,
            int criticalCount,
            int highCount,
            int moderateCount,
            int totalDiscrepancies,
            List<String> matchedMedications,
            List<ReconciliationDiscrepancy> discrepancies
    ) {}

    public record RedactedReconciliationContext(
            RedactedClinicalPayload redactedPayload,
            Map<String, Integer> fhirResourceCountsByType,
            DeterministicReconciliationBaseline deterministicBaseline,
            int totalFhirResourcesProcessed
    ) {}

    public record MedicationReconciliationResult(
            UUID draftId,
            String attestationStatus,
            ReconciliationTransition transitionType,
            String modelId,
            Map<String, Integer> processedFhirResourceCounts,
            DeterministicReconciliationBaseline deterministicBaseline,
            JsonNode aiReconciliationSynthesis,
            int totalPhiRedactions,
            Map<String, Integer> redactionCountsByCategory,
            long latencyMs
    ) {}

    private record NormalizedMedSignal(
            String genericName,
            String displayText,
            Double doseValue,
            String doseUnit,
            String normalizedFrequency,
            String status,
            MedicationSource source
    ) {}

    private static final Pattern DOSE_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(mg|mcg|g|ml|units?|iu|meq)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Map<String, String> BRAND_TO_GENERIC = Map.ofEntries(
            Map.entry("coumadin", "warfarin"),
            Map.entry("jantoven", "warfarin"),
            Map.entry("glucophage", "metformin"),
            Map.entry("lipitor", "atorvastatin"),
            Map.entry("zocor", "simvastatin"),
            Map.entry("crestor", "rosuvastatin"),
            Map.entry("lasix", "furosemide"),
            Map.entry("norvasc", "amlodipine"),
            Map.entry("plavix", "clopidogrel"),
            Map.entry("eliquis", "apixaban"),
            Map.entry("xarelto", "rivaroxaban"),
            Map.entry("lantus", "insulin glargine"),
            Map.entry("humalog", "insulin lispro"),
            Map.entry("novolog", "insulin aspart"),
            Map.entry("synthroid", "levothyroxine"),
            Map.entry("zestril", "lisinopril"),
            Map.entry("prinivil", "lisinopril"),
            Map.entry("cozaar", "losartan"),
            Map.entry("diovan", "valsartan"),
            Map.entry("coreg", "carvedilol"),
            Map.entry("lopressor", "metoprolol"),
            Map.entry("toprol", "metoprolol"),
            Map.entry("prilosec", "omeprazole"),
            Map.entry("nexium", "esomeprazole"),
            Map.entry("advil", "ibuprofen"),
            Map.entry("motrin", "ibuprofen"),
            Map.entry("aleve", "naproxen"),
            Map.entry("ecotrin", "aspirin"),
            Map.entry("augmentin", "amoxicillin"),
            Map.entry("amoxil", "amoxicillin"),
            Map.entry("lanoxin", "digoxin")
    );

    private static final Set<String> KNOWN_GENERIC_LEXICON = Set.of(
            "warfarin", "metformin", "atorvastatin", "simvastatin", "rosuvastatin",
            "furosemide", "amlodipine", "clopidogrel", "apixaban", "rivaroxaban",
            "insulin glargine", "insulin lispro", "insulin aspart", "insulin",
            "levothyroxine", "lisinopril", "enalapril", "ramipril", "losartan",
            "valsartan", "carvedilol", "metoprolol", "atenolol", "omeprazole",
            "pantoprazole", "esomeprazole", "ibuprofen", "naproxen", "aspirin",
            "amoxicillin", "ampicillin", "penicillin", "azithromycin", "ciprofloxacin",
            "digoxin", "hydrochlorothiazide", "spironolactone", "gabapentin",
            "levetiracetam", "phenytoin", "prednisone", "albuterol", "sertraline"
    );

    private static final Set<String> HIGH_ALERT_MEDICATIONS = Set.of(
            "warfarin", "apixaban", "rivaroxaban", "insulin glargine", "insulin lispro",
            "insulin aspart", "insulin", "digoxin", "levothyroxine", "metoprolol",
            "carvedilol", "clopidogrel", "phenytoin", "levetiracetam"
    );

    private static final Map<String, String> THERAPEUTIC_CLASS_BY_DRUG = Map.ofEntries(
            Map.entry("warfarin", "ANTICOAGULANT"),
            Map.entry("apixaban", "ANTICOAGULANT"),
            Map.entry("rivaroxaban", "ANTICOAGULANT"),
            Map.entry("atorvastatin", "STATIN"),
            Map.entry("simvastatin", "STATIN"),
            Map.entry("rosuvastatin", "STATIN"),
            Map.entry("lisinopril", "RAAS_INHIBITOR"),
            Map.entry("enalapril", "RAAS_INHIBITOR"),
            Map.entry("ramipril", "RAAS_INHIBITOR"),
            Map.entry("losartan", "RAAS_INHIBITOR"),
            Map.entry("valsartan", "RAAS_INHIBITOR"),
            Map.entry("metoprolol", "BETA_BLOCKER"),
            Map.entry("carvedilol", "BETA_BLOCKER"),
            Map.entry("atenolol", "BETA_BLOCKER"),
            Map.entry("ibuprofen", "NSAID"),
            Map.entry("naproxen", "NSAID"),
            Map.entry("omeprazole", "PROTON_PUMP_INHIBITOR"),
            Map.entry("pantoprazole", "PROTON_PUMP_INHIBITOR"),
            Map.entry("esomeprazole", "PROTON_PUMP_INHIBITOR")
    );

    private static final Set<String> PENICILLIN_CROSS_REACTIVE = Set.of(
            "amoxicillin", "ampicillin", "penicillin"
    );

    private static final Set<String> NON_DISPENSED_STATUSES = Set.of(
            "declined", "cancelled", "canceled", "stopped", "not-dispensed", "abandoned", "overdue", "refill-overdue"
    );

    private final GeminiClinicalApiClient geminiClient;
    private final PhiRedactionGateway phiRedactionGateway;
    private final ClinicalAiGovernanceService governanceService;
    private final ObjectMapper objectMapper;

    public MedicationReconciliationEngine(
            GeminiClinicalApiClient geminiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService governanceService) {
        this.geminiClient = Objects.requireNonNull(geminiClient, "GeminiClinicalApiClient is required");
        this.phiRedactionGateway = Objects.requireNonNull(phiRedactionGateway, "PhiRedactionGateway is required");
        this.governanceService = Objects.requireNonNull(governanceService, "ClinicalAiGovernanceService is required");
        this.objectMapper = geminiClient.getObjectMapper();
    }

    /**
     * Performs full three-way medication reconciliation combining mandatory HIPAA Safe-Harbor
     * PHI redaction, deterministic discrepancy detection, structured Gemini clinical synthesis
     * ({@code gemini-4-hydrogen}), and {@code DRAFT_PENDING_ATTESTATION} clinician review quarantine.
     */
    public MedicationReconciliationResult reconcileMedications(MedicationReconciliationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("MedicationReconciliationRequest is required.");
        }
        governanceService.assertTenantAiAllowed(request.facilityId(), AiCapabilityType.MEDICATION_RECONCILIATION);

        ReconciliationTransition transition = request.transitionType() != null
                ? request.transitionType()
                : ReconciliationTransition.ADMISSION;

        RedactedReconciliationContext context = prepareRedactedReconciliationContext(request);
        if (context.redactedPayload().redactedText().isBlank()) {
            throw new IllegalArgumentException(
                    "At least one medication source (currentMedicationList, admissionNotesText, pharmacyRecords, or fhirResourcesJson) is required.");
        }

        String systemInstruction = """
                You are a board-certified clinical pharmacist and medication safety specialist embedded
                inside an HL7 FHIR R4 Electronic Health Record (EHR).
                Perform a rigorous three-way medication reconciliation comparing:
                (1) the patient's current EHR medication list,
                (2) clinical admission/encounter notes, and
                (3) external pharmacy dispense records (plus FHIR Medication and Allergy resources).
                Safety Rules:
                1. Preserve all deterministic discrepancies supplied in the input and identify any
                   additional subtle clinical discrepancies (e.g., tapered doses, PRN vs scheduled
                   conflicts, formulation/extended-release mismatches, undocumented OTC/herbal agents).
                2. Clearly distinguish intentional therapeutic changes documented in admission notes
                   from potential unintentional omissions, commissions, dose mismatches, or duplications.
                3. Flag high-alert medications (anticoagulants, insulin, antiarrhythmics, beta-blockers,
                   antiepileptics, thyroid hormones) with explicit urgency for clinician verification.
                4. Never finalize or auto-approve orders; output actionable clinician/pharmacist review
                   recommendations and targeted patient/pharmacy clarification questions.
                5. Preserve all Safe-Harbor redaction placeholders verbatim. Output strictly valid JSON
                   matching responseSchema.
                """;

        String prompt = "Care Transition: " + transition.name()
                + "\nDeterministic Discrepancy Count: " + context.deterministicBaseline().totalDiscrepancies()
                + " (Critical=" + context.deterministicBaseline().criticalCount()
                + ", High=" + context.deterministicBaseline().highCount()
                + ", Moderate=" + context.deterministicBaseline().moderateCount() + ")"
                + "\nDeterministic Findings Summary:\n" + formatDeterministicBaseline(context.deterministicBaseline())
                + "\n\nDe-Identified Three-Way Medication Reconciliation Dossier:\n"
                + context.redactedPayload().redactedText();

        TextGenerationResult aiResult = geminiClient.generateClinicalContent(
                systemInstruction,
                prompt,
                List.of(),
                buildReconciliationResponseSchema(),
                "MEDIUM"
        );

        JsonNode structuredSynthesis = parseJsonSafely(aiResult.text());
        String actor = (request.clinicianId() == null || request.clinicianId().isBlank())
                ? "CLINICAL_PHARMACIST"
                : request.clinicianId().trim();

        ClinicalAiDraftRecord draft = governanceService.registerDraft(
                request.facilityId(),
                request.patientId(),
                AiCapabilityType.MEDICATION_RECONCILIATION,
                aiResult.modelId(),
                context.redactedPayload().redactedSha256Hex(),
                context.redactedPayload().redactionCountsByCategory(),
                aiResult.text(),
                aiResult.tokenUsage(),
                aiResult.latencyMs(),
                actor
        );

        return new MedicationReconciliationResult(
                draft.draftId(),
                draft.status().name(),
                transition,
                aiResult.modelId(),
                context.fhirResourceCountsByType(),
                context.deterministicBaseline(),
                structuredSynthesis,
                draft.totalPhiRedactions(),
                draft.redactionCountsByCategory(),
                aiResult.latencyMs()
        );
    }

    /**
     * Prepares the Safe-Harbor redacted three-way medication reconciliation context and executes
     * the deterministic discrepancy engine locally without invoking any external model.
     */
    public RedactedReconciliationContext prepareRedactedReconciliationContext(MedicationReconciliationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("MedicationReconciliationRequest is required.");
        }

        Set<String> harvestedNames = new LinkedHashSet<>();
        Set<String> harvestedIds = new LinkedHashSet<>();
        String harvestedDob = request.knownDateOfBirth();

        if (request.knownPatientNames() != null) {
            request.knownPatientNames().stream()
                    .filter(s -> s != null && !s.isBlank())
                    .forEach(s -> harvestedNames.add(s.trim()));
        }
        if (request.knownIdentifiers() != null) {
            request.knownIdentifiers().stream()
                    .filter(s -> s != null && !s.isBlank())
                    .forEach(s -> harvestedIds.add(s.trim()));
        }

        Map<String, Integer> fhirCounts = new LinkedHashMap<>();
        List<String> sanitizedFhirSummaries = new ArrayList<>();
        List<NormalizedMedSignal> fhirListSignals = new ArrayList<>();
        List<NormalizedMedSignal> fhirDispenseSignals = new ArrayList<>();
        Set<String> harvestedAllergies = new LinkedHashSet<>();

        if (request.documentedAllergies() != null) {
            request.documentedAllergies().stream()
                    .filter(a -> a != null && !a.isBlank())
                    .forEach(a -> harvestedAllergies.add(a.trim()));
        }

        int totalFhirProcessed = 0;
        if (request.fhirResourcesJson() != null) {
            for (String rawJson : request.fhirResourcesJson()) {
                if (rawJson == null || rawJson.isBlank()) {
                    continue;
                }
                List<JsonNode> unwrapped = unwrapFhirResources(rawJson);
                for (JsonNode res : unwrapped) {
                    totalFhirProcessed++;
                    String rt = res.path("resourceType").asText("Unknown");
                    fhirCounts.merge(rt, 1, Integer::sum);

                    if ("Patient".equalsIgnoreCase(rt) || "RelatedPerson".equalsIgnoreCase(rt)) {
                        harvestDobIfMissing(res, harvestedNames, harvestedIds);
                        if ((harvestedDob == null || harvestedDob.isBlank()) && res.has("birthDate")) {
                            harvestedDob = res.path("birthDate").asText("");
                        }
                        sanitizedFhirSummaries.add(summarizeSanitizedPatientDemographics(res));
                        continue;
                    }

                    if ("AllergyIntolerance".equalsIgnoreCase(rt)) {
                        String allergyText = extractCodeableConceptText(res.path("code"));
                        if (!allergyText.isBlank()) {
                            harvestedAllergies.add(allergyText);
                        }
                    } else if ("MedicationRequest".equalsIgnoreCase(rt) || "MedicationStatement".equalsIgnoreCase(rt)) {
                        NormalizedMedSignal sig = extractFhirMedicationSignal(res,
                                "MedicationRequest".equalsIgnoreCase(rt)
                                        ? MedicationSource.FHIR_MEDICATION_REQUEST
                                        : MedicationSource.FHIR_MEDICATION_STATEMENT);
                        if (sig != null) {
                            fhirListSignals.add(sig);
                        }
                    } else if ("MedicationDispense".equalsIgnoreCase(rt)) {
                        NormalizedMedSignal sig = extractFhirMedicationSignal(res, MedicationSource.FHIR_MEDICATION_DISPENSE);
                        if (sig != null) {
                            fhirDispenseSignals.add(sig);
                        }
                    }

                    sanitizedFhirSummaries.add(summarizeSanitizedFhirResource(res));
                }
            }
        }

        DeterministicReconciliationBaseline baseline = detectDeterministicDiscrepancies(
                request.currentMedicationList(),
                request.admissionNotesText(),
                request.pharmacyRecords(),
                fhirListSignals,
                fhirDispenseSignals,
                new ArrayList<>(harvestedAllergies)
        );

        StringBuilder assembled = new StringBuilder();
        if (request.currentMedicationList() != null && !request.currentMedicationList().isEmpty()) {
            assembled.append("=== CURRENT EHR MEDICATION LIST ===\n");
            for (MedicationEntry entry : request.currentMedicationList()) {
                if (entry == null || entry.drugName() == null || entry.drugName().isBlank()) {
                    continue;
                }
                assembled.append("- ")
                        .append(entry.drugName().trim())
                        .append(formatOptional(" | RxNorm=", entry.rxNormCode()))
                        .append(formatOptional(" | Dose=", joinDoseUnit(entry.dose(), entry.unit())))
                        .append(formatOptional(" | Freq=", entry.frequency()))
                        .append(formatOptional(" | Route=", entry.route()))
                        .append(formatOptional(" | Status=", entry.status()))
                        .append(formatOptional(" | Notes=", entry.notes()))
                        .append("\n");
            }
        }

        if (request.admissionNotesText() != null && !request.admissionNotesText().isBlank()) {
            assembled.append("\n=== ADMISSION / ENCOUNTER NOTES ===\n")
                    .append(request.admissionNotesText().trim())
                    .append("\n");
        }

        if (request.pharmacyRecords() != null && !request.pharmacyRecords().isEmpty()) {
            assembled.append("\n=== EXTERNAL PHARMACY DISPENSE RECORDS ===\n");
            for (PharmacyDispenseRecord rec : request.pharmacyRecords()) {
                if (rec == null || rec.drugName() == null || rec.drugName().isBlank()) {
                    continue;
                }
                assembled.append("- ")
                        .append(rec.drugName().trim())
                        .append(formatOptional(" | RxNorm=", rec.rxNormCode()))
                        .append(formatOptional(" | Strength/Dose=", rec.strengthAndDose()))
                        .append(formatOptional(" | Sig=", rec.sigInstructions()))
                        .append(formatOptional(" | Status=", rec.dispenseStatus()))
                        .append(rec.daysSupply() != null ? " | DaysSupply=" + rec.daysSupply() : "")
                        .append(formatOptional(" | LastFill=", rec.lastFillDate()))
                        .append(formatOptional(" | Pharmacy=", rec.pharmacyName()))
                        .append("\n");
            }
        }

        if (!harvestedAllergies.isEmpty()) {
            assembled.append("\n=== DOCUMENTED ALLERGIES ===\n");
            for (String allergy : harvestedAllergies) {
                assembled.append("- ").append(allergy).append("\n");
            }
        }

        if (!sanitizedFhirSummaries.isEmpty()) {
            assembled.append("\n=== HL7 FHIR R4 SANITIZED RESOURCES ===\n");
            for (String line : sanitizedFhirSummaries) {
                assembled.append(line).append("\n");
            }
        }

        RedactionContext redactionContext = new RedactionContext(
                new ArrayList<>(harvestedNames),
                new ArrayList<>(harvestedIds),
                harvestedDob
        );
        RedactedClinicalPayload redactedPayload = phiRedactionGateway.redact(assembled.toString().trim(), redactionContext);

        return new RedactedReconciliationContext(
                redactedPayload,
                Collections.unmodifiableMap(fhirCounts),
                baseline,
                totalFhirProcessed
        );
    }

    /**
     * Deterministically compares the patient's current EHR medication list, admission notes,
     * external pharmacy dispense records, and FHIR medication/allergy resources to identify
     * potential discrepancies for clinician review.
     */
    public DeterministicReconciliationBaseline detectDeterministicDiscrepancies(
            List<MedicationEntry> currentMedicationList,
            String admissionNotesText,
            List<PharmacyDispenseRecord> pharmacyRecords,
            List<String> documentedAllergies) {
        return detectDeterministicDiscrepancies(
                currentMedicationList,
                admissionNotesText,
                pharmacyRecords,
                List.of(),
                List.of(),
                documentedAllergies
        );
    }

    private DeterministicReconciliationBaseline detectDeterministicDiscrepancies(
            List<MedicationEntry> currentMedicationList,
            String admissionNotesText,
            List<PharmacyDispenseRecord> pharmacyRecords,
            List<NormalizedMedSignal> fhirListSignals,
            List<NormalizedMedSignal> fhirDispenseSignals,
            List<String> documentedAllergies) {

        Map<String, NormalizedMedSignal> currentMap = new LinkedHashMap<>();
        int currentCount = 0;
        if (currentMedicationList != null) {
            for (MedicationEntry entry : currentMedicationList) {
                if (entry == null || entry.drugName() == null || entry.drugName().isBlank()) {
                    continue;
                }
                if (isInactiveStatus(entry.status())) {
                    continue;
                }
                currentCount++;
                String generic = normalizeDrugName(entry.drugName());
                String combinedDose = joinDoseUnit(entry.dose(), entry.unit());
                if (combinedDose.isBlank()) {
                    combinedDose = entry.drugName();
                }
                DosePair dosePair = extractDose(combinedDose);
                String freq = normalizeFrequency(entry.frequency() != null ? entry.frequency() : entry.notes());
                currentMap.putIfAbsent(generic, new NormalizedMedSignal(
                        generic,
                        formatDisplay(entry.drugName(), joinDoseUnit(entry.dose(), entry.unit()), entry.frequency()),
                        dosePair.value(),
                        dosePair.unit(),
                        freq,
                        entry.status() != null ? entry.status().trim() : "active",
                        entry.source() != null ? entry.source() : MedicationSource.CURRENT_MEDICATION_LIST
                ));
            }
        }
        for (NormalizedMedSignal fhirMed : fhirListSignals) {
            if (!isInactiveStatus(fhirMed.status())) {
                currentMap.putIfAbsent(fhirMed.genericName(), fhirMed);
            }
        }

        Map<String, NormalizedMedSignal> pharmacyMap = new LinkedHashMap<>();
        int pharmacyCount = 0;
        if (pharmacyRecords != null) {
            for (PharmacyDispenseRecord rec : pharmacyRecords) {
                if (rec == null || rec.drugName() == null || rec.drugName().isBlank()) {
                    continue;
                }
                pharmacyCount++;
                String generic = normalizeDrugName(rec.drugName());
                String doseSource = (rec.strengthAndDose() != null && !rec.strengthAndDose().isBlank())
                        ? rec.strengthAndDose()
                        : (rec.drugName() + " " + (rec.sigInstructions() != null ? rec.sigInstructions() : ""));
                DosePair dosePair = extractDose(doseSource);
                String freq = normalizeFrequency(rec.sigInstructions());
                String status = (rec.dispenseStatus() == null || rec.dispenseStatus().isBlank())
                        ? "completed"
                        : rec.dispenseStatus().trim();
                pharmacyMap.putIfAbsent(generic, new NormalizedMedSignal(
                        generic,
                        formatDisplay(rec.drugName(), rec.strengthAndDose(), rec.sigInstructions()),
                        dosePair.value(),
                        dosePair.unit(),
                        freq,
                        status,
                        MedicationSource.PHARMACY_RECORD
                ));
            }
        }
        for (NormalizedMedSignal fhirDisp : fhirDispenseSignals) {
            pharmacyMap.putIfAbsent(fhirDisp.genericName(), fhirDisp);
        }

        Map<String, NormalizedMedSignal> admissionMap = extractMedicationsFromAdmissionNotes(
                admissionNotesText, currentMap.keySet(), pharmacyMap.keySet());

        boolean hasAdmissionNotes = admissionNotesText != null && !admissionNotesText.isBlank();
        List<ReconciliationDiscrepancy> discrepancies = new ArrayList<>();
        Set<String> matchedMeds = new LinkedHashSet<>();

        Set<String> allDrugs = new LinkedHashSet<>();
        allDrugs.addAll(currentMap.keySet());
        allDrugs.addAll(pharmacyMap.keySet());
        allDrugs.addAll(admissionMap.keySet());

        for (String drug : allDrugs) {
            NormalizedMedSignal current = currentMap.get(drug);
            NormalizedMedSignal pharmacy = pharmacyMap.get(drug);
            NormalizedMedSignal admission = admissionMap.get(drug);
            boolean highAlert = HIGH_ALERT_MEDICATIONS.contains(drug);

            String currentDetail = current != null ? current.displayText() : "NOT_LISTED";
            String admissionDetail = admission != null ? admission.displayText() : "NOT_MENTIONED";
            String pharmacyDetail = pharmacy != null
                    ? pharmacy.displayText() + " [" + pharmacy.status() + "]"
                    : "NO_PHARMACY_RECORD";

            boolean drugHasDiscrepancy = false;

            // 1. Pharmacy dispense gap / non-adherence check
            if (pharmacy != null && isNonDispensedStatus(pharmacy.status())) {
                drugHasDiscrepancy = true;
                discrepancies.add(new ReconciliationDiscrepancy(
                        "MREC-ADHERENCE-001",
                        DiscrepancyCategory.ADHERENCE_OR_FILL_GAP,
                        highAlert ? DiscrepancySeverity.HIGH : DiscrepancySeverity.MODERATE,
                        drug,
                        currentDetail,
                        admissionDetail,
                        pharmacyDetail,
                        "Pharmacy record indicates fill status '" + pharmacy.status()
                                + "' for " + drug + ", creating risk of therapy interruption or rebound exacerbation.",
                        "Confirm actual patient home intake, address cost/adherence barriers, and verify safe re-initiation dose."
                ));
            }

            // 2. Unlisted active pharmacy dispense missing from EHR current medication list
            if (current == null && pharmacy != null && !isNonDispensedStatus(pharmacy.status())) {
                drugHasDiscrepancy = true;
                discrepancies.add(new ReconciliationDiscrepancy(
                        "MREC-OMISSION-002",
                        DiscrepancyCategory.UNINTENTIONAL_OMISSION,
                        highAlert ? DiscrepancySeverity.CRITICAL_SAFETY_RISK : DiscrepancySeverity.HIGH,
                        drug,
                        currentDetail,
                        admissionDetail,
                        pharmacyDetail,
                        "Active external pharmacy fill for " + drug
                                + " is absent from the EHR current medication list, risking unrecognized interactions or omission.",
                        "Verify with patient/pharmacy whether " + drug
                                + " is actively taken and update the EHR active medication list accordingly."
                ));
            }

            // 3. Unintentional omission from admission notes when active in current list or pharmacy fills
            if (hasAdmissionNotes && admission == null
                    && (current != null || (pharmacy != null && !isNonDispensedStatus(pharmacy.status())))) {
                drugHasDiscrepancy = true;
                discrepancies.add(new ReconciliationDiscrepancy(
                        "MREC-OMISSION-001",
                        DiscrepancyCategory.UNINTENTIONAL_OMISSION,
                        highAlert ? DiscrepancySeverity.CRITICAL_SAFETY_RISK : DiscrepancySeverity.HIGH,
                        drug,
                        currentDetail,
                        admissionDetail,
                        pharmacyDetail,
                        "Active home therapy (" + drug
                                + ") documented in EHR/pharmacy records is omitted from admission notes"
                                + (highAlert ? " (HIGH-ALERT medication)." : "."),
                        "Clarify with admitting clinician whether " + drug
                                + " was intentionally held or unintentionally omitted at care transition."
                ));
            }

            // 4. Unexplained commission in admission notes absent from both EHR list and pharmacy records
            if (admission != null && current == null && pharmacy == null) {
                drugHasDiscrepancy = true;
                discrepancies.add(new ReconciliationDiscrepancy(
                        "MREC-COMMISSION-001",
                        DiscrepancyCategory.UNINTENTIONAL_COMMISSION,
                        highAlert ? DiscrepancySeverity.HIGH : DiscrepancySeverity.MODERATE,
                        drug,
                        currentDetail,
                        admissionDetail,
                        pharmacyDetail,
                        "Medication (" + drug
                                + ") is documented in admission notes but has no corresponding active EHR medication list entry or pharmacy fill.",
                        "Verify whether " + drug
                                + " is a newly started admission order, OTC/cash-pay medication, or documentation error."
                ));
            }

            // 5. Dose / strength mismatch across sources
            if (hasDoseConflict(current, admission, pharmacy)) {
                drugHasDiscrepancy = true;
                discrepancies.add(new ReconciliationDiscrepancy(
                        "MREC-DOSE-001",
                        DiscrepancyCategory.DOSE_MISMATCH,
                        highAlert ? DiscrepancySeverity.CRITICAL_SAFETY_RISK : DiscrepancySeverity.HIGH,
                        drug,
                        currentDetail,
                        admissionDetail,
                        pharmacyDetail,
                        "Conflicting dosage strengths detected for " + drug
                                + " across EHR list, admission notes, and pharmacy dispense records.",
                        "Verify exact tablet strength and daily total dose from prescription bottle or dispensing pharmacy before signing orders."
                ));
            }

            // 6. Dosing frequency mismatch across sources
            if (hasFrequencyConflict(current, admission, pharmacy)) {
                drugHasDiscrepancy = true;
                discrepancies.add(new ReconciliationDiscrepancy(
                        "MREC-FREQ-001",
                        DiscrepancyCategory.FREQUENCY_MISMATCH,
                        highAlert ? DiscrepancySeverity.CRITICAL_SAFETY_RISK : DiscrepancySeverity.HIGH,
                        drug,
                        currentDetail,
                        admissionDetail,
                        pharmacyDetail,
                        "Conflicting dosing schedules detected for " + drug
                                + " across reconciled sources, creating overdose or sub-therapeutic exposure risk.",
                        "Confirm actual patient dosing schedule (QD vs BID/TID/PRN) and reconcile active SIG instructions."
                ));
            }

            // 7. Allergy contraindication check
            String matchedAllergy = findAllergyMatch(drug, documentedAllergies);
            if (matchedAllergy != null) {
                drugHasDiscrepancy = true;
                discrepancies.add(new ReconciliationDiscrepancy(
                        "MREC-ALLERGY-001",
                        DiscrepancyCategory.ALLERGY_CONTRAINDICATION,
                        DiscrepancySeverity.CRITICAL_SAFETY_RISK,
                        drug,
                        currentDetail,
                        admissionDetail,
                        pharmacyDetail,
                        "Reconciled medication " + drug
                                + " conflicts with documented allergy/intolerance ('" + matchedAllergy + "').",
                        "Hold " + drug + " immediately, verify allergy severity/reaction history, and consult prescribing clinician for safe alternative."
                ));
            }

            if (!drugHasDiscrepancy) {
                matchedMeds.add(drug);
            }
        }

        // 8. Therapeutic class duplication across active reconciled drugs
        Map<String, List<String>> drugsByClass = new LinkedHashMap<>();
        for (String drug : allDrugs) {
            String therapeuticClass = THERAPEUTIC_CLASS_BY_DRUG.get(drug);
            if (therapeuticClass != null) {
                drugsByClass.computeIfAbsent(therapeuticClass, k -> new ArrayList<>()).add(drug);
            }
        }
        for (Map.Entry<String, List<String>> entry : drugsByClass.entrySet()) {
            if (entry.getValue().size() >= 2) {
                String joined = String.join(" + ", entry.getValue());
                boolean criticalClass = "ANTICOAGULANT".equals(entry.getKey()) || "BETA_BLOCKER".equals(entry.getKey());
                discrepancies.add(new ReconciliationDiscrepancy(
                        "MREC-DUP-001",
                        DiscrepancyCategory.THERAPEUTIC_DUPLICATION,
                        criticalClass ? DiscrepancySeverity.CRITICAL_SAFETY_RISK : DiscrepancySeverity.HIGH,
                        joined,
                        formatClassPresence(entry.getValue(), currentMap),
                        formatClassPresence(entry.getValue(), admissionMap),
                        formatClassPresence(entry.getValue(), pharmacyMap),
                        "Therapeutic class duplication (" + entry.getKey() + ") detected across reconciled sources: " + joined + ".",
                        "Confirm whether a therapeutic switch occurred and discontinue the superseded duplicate agent."
                ));
            }
        }

        int critical = 0;
        int high = 0;
        int moderate = 0;
        for (ReconciliationDiscrepancy d : discrepancies) {
            switch (d.severity()) {
                case CRITICAL_SAFETY_RISK -> critical++;
                case HIGH -> high++;
                case MODERATE -> moderate++;
                case LOW -> { }
            }
        }

        return new DeterministicReconciliationBaseline(
                currentCount,
                admissionMap.size(),
                pharmacyCount,
                fhirListSignals.size() + fhirDispenseSignals.size(),
                critical,
                high,
                moderate,
                discrepancies.size(),
                List.copyOf(matchedMeds),
                List.copyOf(discrepancies)
        );
    }

    // -------------------------------------------------------------------------
    // Internal Normalization, Parsing & Schema Helpers
    // -------------------------------------------------------------------------

    private Map<String, NormalizedMedSignal> extractMedicationsFromAdmissionNotes(
            String admissionNotesText,
            Set<String> currentDrugKeys,
            Set<String> pharmacyDrugKeys) {
        Map<String, NormalizedMedSignal> found = new LinkedHashMap<>();
        if (admissionNotesText == null || admissionNotesText.isBlank()) {
            return found;
        }

        Set<String> candidateTerms = new LinkedHashSet<>();
        candidateTerms.addAll(KNOWN_GENERIC_LEXICON);
        candidateTerms.addAll(BRAND_TO_GENERIC.keySet());
        candidateTerms.addAll(currentDrugKeys);
        candidateTerms.addAll(pharmacyDrugKeys);

        String lowerNote = admissionNotesText.toLowerCase(Locale.ROOT);
        for (String term : candidateTerms) {
            if (term == null || term.length() < 4) {
                continue;
            }
            Pattern termPattern = Pattern.compile("\\b" + Pattern.quote(term) + "\\b", Pattern.CASE_INSENSITIVE);
            Matcher matcher = termPattern.matcher(lowerNote);
            if (!matcher.find()) {
                continue;
            }
            int start = matcher.start();
            int end = Math.min(admissionNotesText.length(), matcher.end() + 72);
            String snippet = admissionNotesText.substring(start, end).replaceAll("[\\r\\n]+", " ").trim();
            // Bound snippet to first sentence/clause boundary for accurate dose/frequency extraction
            int clauseEnd = findClauseBoundary(snippet);
            String boundedSnippet = clauseEnd > 0 ? snippet.substring(0, clauseEnd).trim() : snippet;

            // Skip explicit negative statements like "stopped warfarin" or "discontinued lisinopril"
            int prefixStart = Math.max(0, start - 28);
            String prefixWindow = lowerNote.substring(prefixStart, start);
            if (prefixWindow.contains("discontinued") || prefixWindow.contains("stopped")
                    || prefixWindow.contains("allergic to") || prefixWindow.contains("allergy to")
                    || prefixWindow.contains("no longer taking")) {
                continue;
            }

            String generic = normalizeDrugName(term);
            DosePair dose = extractDose(boundedSnippet);
            String freq = normalizeFrequency(boundedSnippet);
            found.putIfAbsent(generic, new NormalizedMedSignal(
                    generic,
                    boundedSnippet,
                    dose.value(),
                    dose.unit(),
                    freq,
                    "documented-in-note",
                    MedicationSource.ADMISSION_NOTE
            ));
        }
        return found;
    }

    private static int findClauseBoundary(String text) {
        int best = -1;
        for (char sep : new char[]{';', '.', '\n'}) {
            int idx = text.indexOf(sep);
            if (idx > 6 && (best == -1 || idx < best)) {
                // Avoid splitting on decimal points inside doses like 12.5mg
                if (sep == '.' && idx > 0 && idx + 1 < text.length()
                        && Character.isDigit(text.charAt(idx - 1)) && Character.isDigit(text.charAt(idx + 1))) {
                    continue;
                }
                best = idx;
            }
        }
        int commaIdx = text.indexOf(',');
        if (commaIdx > 10 && (best == -1 || commaIdx < best)) {
            best = commaIdx;
        }
        return best;
    }

    private static String normalizeDrugName(String rawDrugName) {
        if (rawDrugName == null || rawDrugName.isBlank()) {
            return "unknown";
        }
        String cleaned = rawDrugName.toLowerCase(Locale.ROOT)
                .replaceAll("\\b\\d+(?:\\.\\d+)?\\s*(?:mg|mcg|g|ml|units?|iu|meq)\\b", " ")
                .replaceAll("\\b(?:oral|tablet|tablets|capsule|capsules|solution|suspension|injection|er|xr|sr|dr|xl|hcl|sodium|potassium|succinate|tartrate|besylate|calcium)\\b", " ")
                .replaceAll("[^a-z\\s-]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        for (Map.Entry<String, String> brand : BRAND_TO_GENERIC.entrySet()) {
            if (cleaned.equals(brand.getKey()) || cleaned.contains(brand.getKey())) {
                return brand.getValue();
            }
        }
        for (String known : KNOWN_GENERIC_LEXICON) {
            if (cleaned.equals(known) || cleaned.contains(known)) {
                return known;
            }
        }
        String[] tokens = cleaned.split("\\s+");
        return tokens.length > 0 && !tokens[0].isBlank() ? tokens[0] : rawDrugName.trim().toLowerCase(Locale.ROOT);
    }

    private record DosePair(Double value, String unit) {}

    private static DosePair extractDose(String text) {
        if (text == null || text.isBlank()) {
            return new DosePair(null, null);
        }
        Matcher matcher = DOSE_PATTERN.matcher(text);
        if (matcher.find()) {
            try {
                double val = Double.parseDouble(matcher.group(1));
                String unit = matcher.group(2).toLowerCase(Locale.ROOT);
                if (unit.startsWith("unit")) {
                    unit = "units";
                }
                if ("g".equals(unit)) {
                    val = val * 1000.0;
                    unit = "mg";
                }
                return new DosePair(val, unit);
            } catch (NumberFormatException ignored) {
                return new DosePair(null, null);
            }
        }
        return new DosePair(null, null);
    }

    private static String normalizeFrequency(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\b(qid|four times daily|4 times daily|every 6 hours|q6h)\\b.*")) {
            return "QID";
        }
        if (lower.matches(".*\\b(tid|three times daily|3 times daily|every 8 hours|q8h)\\b.*")) {
            return "TID";
        }
        if (lower.matches(".*\\b(bid|twice daily|2 times daily|two times daily|every 12 hours|q12h)\\b.*")) {
            return "BID";
        }
        if (lower.matches(".*\\b(qd|qdaily|once daily|daily|every day|q24h|qhs|at bedtime|nightly|every morning|qAM)\\b.*")) {
            return "QD";
        }
        if (lower.matches(".*\\b(weekly|once weekly|qweek)\\b.*")) {
            return "WEEKLY";
        }
        if (lower.matches(".*\\b(prn|as needed)\\b.*")) {
            return "PRN";
        }
        return null;
    }

    private static boolean hasDoseConflict(
            NormalizedMedSignal current,
            NormalizedMedSignal admission,
            NormalizedMedSignal pharmacy) {
        List<NormalizedMedSignal> withDoses = new ArrayList<>();
        if (current != null && current.doseValue() != null) withDoses.add(current);
        if (admission != null && admission.doseValue() != null) withDoses.add(admission);
        if (pharmacy != null && pharmacy.doseValue() != null) withDoses.add(pharmacy);
        if (withDoses.size() < 2) {
            return false;
        }
        NormalizedMedSignal ref = withDoses.get(0);
        for (int i = 1; i < withDoses.size(); i++) {
            NormalizedMedSignal other = withDoses.get(i);
            if (Objects.equals(ref.doseUnit(), other.doseUnit())
                    && Math.abs(ref.doseValue() - other.doseValue()) > 0.01) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFrequencyConflict(
            NormalizedMedSignal current,
            NormalizedMedSignal admission,
            NormalizedMedSignal pharmacy) {
        Set<String> distinctFreqs = new LinkedHashSet<>();
        if (current != null && current.normalizedFrequency() != null) distinctFreqs.add(current.normalizedFrequency());
        if (admission != null && admission.normalizedFrequency() != null) distinctFreqs.add(admission.normalizedFrequency());
        if (pharmacy != null && pharmacy.normalizedFrequency() != null) distinctFreqs.add(pharmacy.normalizedFrequency());
        return distinctFreqs.size() >= 2;
    }

    private static String findAllergyMatch(String normalizedDrug, List<String> documentedAllergies) {
        if (documentedAllergies == null || documentedAllergies.isEmpty()) {
            return null;
        }
        for (String allergy : documentedAllergies) {
            if (allergy == null || allergy.isBlank()) {
                continue;
            }
            String lowerAllergy = allergy.toLowerCase(Locale.ROOT);
            if (lowerAllergy.contains(normalizedDrug) || normalizedDrug.contains(normalizeDrugName(allergy))) {
                return allergy;
            }
            if ((lowerAllergy.contains("penicillin") || lowerAllergy.contains("amoxicillin") || lowerAllergy.contains("beta-lactam"))
                    && PENICILLIN_CROSS_REACTIVE.contains(normalizedDrug)) {
                return allergy;
            }
            if (lowerAllergy.contains("nsaid") && "NSAID".equals(THERAPEUTIC_CLASS_BY_DRUG.get(normalizedDrug))) {
                return allergy;
            }
        }
        return null;
    }

    private static boolean isInactiveStatus(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String lower = status.trim().toLowerCase(Locale.ROOT);
        return lower.equals("stopped") || lower.equals("cancelled") || lower.equals("canceled")
                || lower.equals("entered-in-error") || lower.equals("inactive") || lower.equals("discontinued");
    }

    private static boolean isNonDispensedStatus(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        return NON_DISPENSED_STATUSES.contains(status.trim().toLowerCase(Locale.ROOT));
    }

    private static String formatClassPresence(List<String> drugs, Map<String, NormalizedMedSignal> sourceMap) {
        List<String> present = new ArrayList<>();
        for (String drug : drugs) {
            NormalizedMedSignal sig = sourceMap.get(drug);
            if (sig != null) {
                present.add(sig.displayText());
            }
        }
        return present.isEmpty() ? "NOT_LISTED" : String.join(" | ", present);
    }

    private static String formatDisplay(String drugName, String dose, String freq) {
        StringBuilder sb = new StringBuilder(drugName.trim());
        if (dose != null && !dose.isBlank() && !drugName.toLowerCase(Locale.ROOT).contains(dose.toLowerCase(Locale.ROOT))) {
            sb.append(" ").append(dose.trim());
        }
        if (freq != null && !freq.isBlank()) {
            sb.append(" (").append(freq.trim()).append(")");
        }
        return sb.toString();
    }

    private static String joinDoseUnit(String dose, String unit) {
        String d = dose != null ? dose.trim() : "";
        String u = unit != null ? unit.trim() : "";
        if (d.isBlank()) return "";
        if (u.isBlank() || d.toLowerCase(Locale.ROOT).endsWith(u.toLowerCase(Locale.ROOT))) {
            return d;
        }
        return d + u;
    }

    private static String formatOptional(String prefix, String value) {
        return (value == null || value.isBlank()) ? "" : prefix + value.trim();
    }

    private List<JsonNode> unwrapFhirResources(String rawJson) {
        List<JsonNode> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            if ("Bundle".equalsIgnoreCase(root.path("resourceType").asText(""))) {
                JsonNode entries = root.path("entry");
                if (entries.isArray()) {
                    for (JsonNode entry : entries) {
                        JsonNode resource = entry.path("resource");
                        if (resource != null && resource.isObject()) {
                            result.add(resource);
                        }
                    }
                }
            } else if (root.isObject()) {
                result.add(root);
            }
        } catch (Exception ignored) {
            // Non-JSON fragments ignored here; still preserved safely if included in narrative text
        }
        return result;
    }

    private void harvestDobIfMissing(JsonNode patientNode, Set<String> names, Set<String> identifiers) {
        JsonNode nameArray = patientNode.path("name");
        if (nameArray.isArray()) {
            for (JsonNode n : nameArray) {
                String family = n.path("family").asText("");
                if (!family.isBlank()) {
                    names.add(family.trim());
                }
                JsonNode given = n.path("given");
                StringBuilder full = new StringBuilder();
                if (given.isArray()) {
                    for (JsonNode g : given) {
                        String gVal = g.asText("");
                        if (!gVal.isBlank()) {
                            names.add(gVal.trim());
                            if (!full.isEmpty()) full.append(' ');
                            full.append(gVal.trim());
                        }
                    }
                }
                if (!family.isBlank()) {
                    if (!full.isEmpty()) full.append(' ');
                    full.append(family.trim());
                }
                if (!full.isEmpty()) {
                    names.add(full.toString());
                }
            }
        }
        JsonNode idArray = patientNode.path("identifier");
        if (idArray.isArray()) {
            for (JsonNode id : idArray) {
                String val = id.path("value").asText("");
                if (!val.isBlank()) {
                    identifiers.add(val.trim());
                }
            }
        }
        JsonNode telecomArray = patientNode.path("telecom");
        if (telecomArray.isArray()) {
            for (JsonNode t : telecomArray) {
                String val = t.path("value").asText("");
                if (!val.isBlank()) {
                    identifiers.add(val.trim());
                }
            }
        }
        JsonNode addressArray = patientNode.path("address");
        if (addressArray.isArray()) {
            for (JsonNode addr : addressArray) {
                JsonNode lines = addr.path("line");
                if (lines.isArray()) {
                    for (JsonNode line : lines) {
                        String val = line.asText("");
                        if (!val.isBlank()) {
                            identifiers.add(val.trim());
                        }
                    }
                }
            }
        }
    }

    private String summarizeSanitizedPatientDemographics(JsonNode patientNode) {
        String gender = patientNode.path("gender").asText("unknown");
        String birthDate = patientNode.path("birthDate").asText("");
        String birthYear = birthDate.length() >= 4 ? birthDate.substring(0, 4) : "unknown";
        return "[Patient] gender=" + gender + " birthYear=" + birthYear;
    }

    private String summarizeSanitizedFhirResource(JsonNode res) {
        String rt = res.path("resourceType").asText("Resource");
        if ("MedicationRequest".equalsIgnoreCase(rt) || "MedicationStatement".equalsIgnoreCase(rt)
                || "MedicationDispense".equalsIgnoreCase(rt)) {
            String med = extractCodeableConceptText(res.path("medicationCodeableConcept"));
            String status = res.path("status").asText("unknown");
            String dosage = extractDosageText(res);
            String note = extractNoteText(res);
            return "[" + rt + "] medication=" + med + " status=" + status
                    + formatOptional(" dosage=", dosage)
                    + formatOptional(" note=", note);
        }
        if ("AllergyIntolerance".equalsIgnoreCase(rt)) {
            return "[AllergyIntolerance] substance=" + extractCodeableConceptText(res.path("code"))
                    + " criticality=" + res.path("criticality").asText("unknown");
        }
        if ("Condition".equalsIgnoreCase(rt)) {
            return "[Condition] " + extractCodeableConceptText(res.path("code"))
                    + " clinicalStatus=" + res.path("clinicalStatus").path("coding").path(0).path("code").asText("unknown");
        }
        ObjectNode sanitized = res.deepCopy();
        sanitized.remove(List.of("name", "identifier", "telecom", "address", "birthDate", "photo", "contact"));
        return "[" + rt + "] " + sanitized.toString();
    }

    private NormalizedMedSignal extractFhirMedicationSignal(JsonNode res, MedicationSource source) {
        String medText = extractCodeableConceptText(res.path("medicationCodeableConcept"));
        if (medText.isBlank()) {
            return null;
        }
        String dosageText = extractDosageText(res);
        String status = res.path("status").asText("active");
        String generic = normalizeDrugName(medText);
        DosePair dose = extractDose(medText + " " + dosageText);
        String freq = normalizeFrequency(dosageText);
        return new NormalizedMedSignal(
                generic,
                formatDisplay(medText, null, dosageText),
                dose.value(),
                dose.unit(),
                freq,
                status,
                source
        );
    }

    private static String extractCodeableConceptText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        String text = node.path("text").asText("");
        if (!text.isBlank()) {
            return text.trim();
        }
        JsonNode coding = node.path("coding");
        if (coding.isArray() && !coding.isEmpty()) {
            String display = coding.path(0).path("display").asText("");
            if (!display.isBlank()) {
                return display.trim();
            }
            return coding.path(0).path("code").asText("").trim();
        }
        return "";
    }

    private static String extractDosageText(JsonNode res) {
        JsonNode dosageArr = res.has("dosageInstruction") ? res.path("dosageInstruction") : res.path("dosage");
        if (dosageArr.isArray() && !dosageArr.isEmpty()) {
            return dosageArr.path(0).path("text").asText("").trim();
        }
        return "";
    }

    private static String extractNoteText(JsonNode res) {
        JsonNode notes = res.path("note");
        if (notes.isArray() && !notes.isEmpty()) {
            return notes.path(0).path("text").asText("").trim();
        }
        return "";
    }

    private String formatDeterministicBaseline(DeterministicReconciliationBaseline baseline) {
        if (baseline.discrepancies().isEmpty()) {
            return "No deterministic discrepancies detected across provided sources.";
        }
        StringBuilder sb = new StringBuilder();
        for (ReconciliationDiscrepancy d : baseline.discrepancies()) {
            sb.append("- [").append(d.ruleCode()).append(" | ").append(d.severity()).append(" | ").append(d.category())
                    .append("] drug=").append(d.normalizedDrugName())
                    .append(" | current=").append(d.currentListDetail())
                    .append(" | admission=").append(d.admissionNoteDetail())
                    .append(" | pharmacy=").append(d.pharmacyRecordDetail())
                    .append(" | impact=").append(d.clinicalImpact())
                    .append("\n");
        }
        return sb.toString();
    }

    private ObjectNode buildReconciliationResponseSchema() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "OBJECT");
        ObjectNode props = objectMapper.createObjectNode();

        props.set("executiveSummary", stringSchema("Concise clinician-ready summary of medication reconciliation status and urgent safety concerns."));

        ObjectNode medItem = objectMapper.createObjectNode();
        medItem.put("type", "OBJECT");
        ObjectNode medProps = objectMapper.createObjectNode();
        medProps.set("drugName", stringSchema("Medication name."));
        medProps.set("normalizedGeneric", stringSchema("Normalized generic INN/RxNorm root."));
        medProps.set("recommendedDoseAndFrequency", stringSchema("Proposed reconciled dose, route, and schedule pending clinician attestation."));
        medProps.set("sourceAgreement", stringSchema("CONCORDANT, PARTIAL_CONFLICT, or DISCORDANT."));
        medProps.set("clinicianAction", stringSchema("CONTINUE, HOLD, MODIFY_DOSE, DISCONTINUE, or CLARIFY_WITH_PATIENT_PHARMACY."));
        medItem.set("properties", medProps);
        props.set("reconciledMedicationPlan", arrayOf(medItem));

        ObjectNode discItem = objectMapper.createObjectNode();
        discItem.put("type", "OBJECT");
        ObjectNode discProps = objectMapper.createObjectNode();
        discProps.set("category", stringSchema("Discrepancy category (OMISSION, COMMISSION, DOSE_MISMATCH, FREQUENCY_MISMATCH, DUPLICATION, ALLERGY, ADHERENCE_GAP)."));
        discProps.set("severity", stringSchema("CRITICAL_SAFETY_RISK, HIGH, MODERATE, or LOW."));
        discProps.set("drugName", stringSchema("Affected medication name."));
        discProps.set("clinicalRationale", stringSchema("Why this discrepancy matters clinically."));
        discProps.set("recommendedResolution", stringSchema("Specific verification or order reconciliation step for the clinician."));
        discItem.set("properties", discProps);
        props.set("identifiedDiscrepancies", arrayOf(discItem));

        props.set("highAlertSafetyWarnings", arrayOf(stringSchema("High-alert medication warnings requiring immediate pharmacist/physician verification.")));
        props.set("clarificationQuestionsForPatientOrPharmacy", arrayOf(stringSchema("Targeted question for patient, caregiver, or community pharmacy.")));

        root.set("properties", props);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("executiveSummary");
        required.add("reconciledMedicationPlan");
        required.add("identifiedDiscrepancies");
        required.add("highAlertSafetyWarnings");
        required.add("clarificationQuestionsForPatientOrPharmacy");
        root.set("required", required);
        return root;
    }

    private ObjectNode stringSchema(String description) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "STRING");
        node.put("description", description);
        return node;
    }

    private ObjectNode arrayOf(ObjectNode itemSchema) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "ARRAY");
        node.set("items", itemSchema);
        return node;
    }

    private JsonNode parseJsonSafely(String rawText) {
        try {
            return objectMapper.readTree(rawText);
        } catch (Exception ex) {
            return objectMapper.createObjectNode().put("rawReconciliationResponse", rawText);
        }
    }
}
