/*
 * =============================================================================
 * Opes HealthCore SDK — Terminology Repositories, Service & Starter Seed Pack
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.terminology.service;

import com.opeshealth.healthcore.terminology.domain.TerminologyEntities.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * High-speed L1 Caffeine-cached clinical terminology engine supporting:
 * <ul>
 *   <li>LOINC (vitals, lab panels), SNOMED CT (findings/allergies), ICD-10/11, RxNorm/ATC</li>
 *   <li>Precomputed SNOMED/ATC {@code IS-A} closure checks without recursive DB queries</li>
 *   <li>UCUM unit conversion ({@code mg/dL <-> mmol/L}, {@code degC <-> degF}, {@code kg <-> lb})</li>
 *   <li>Bulk CSV/RF2 ingestion and out-of-the-box primary/tropical/hospital starter seed pack</li>
 * </ul>
 */
public class TerminologyService implements ApplicationRunner {

    public static final String LOINC = "http://loinc.org";
    public static final String SNOMED = "http://snomed.info/sct";
    public static final String ICD10 = "http://hl7.org/fhir/sid/icd-10";
    public static final String ICD11 = "http://id.who.int/icd/release/11/mms";
    public static final String RXNORM = "http://www.nlm.nih.gov/research/umls/rxnorm";
    public static final String ATC = "http://www.whocc.no/atc";
    public static final String CVX = "http://hl7.org/fhir/sid/cvx";

    public interface CodeConceptRepository extends JpaRepository<CodeConceptEntity, UUID> {
        Optional<CodeConceptEntity> findBySystemUriAndCode(String systemUri, String code);
        List<CodeConceptEntity> findBySystemUriAndDisplayEnContainingIgnoreCase(
                String systemUri, String query, org.springframework.data.domain.Pageable pageable);
    }

    public interface ConceptClosureRepository extends JpaRepository<ConceptClosureEntity, UUID> {
        boolean existsBySystemUriAndAncestorCodeAndDescendantCode(
                String systemUri, String ancestorCode, String descendantCode);
    }

    public interface ConceptMapRepository extends JpaRepository<ConceptMapEntity, UUID> {
        List<ConceptMapEntity> findBySourceSystemAndSourceCode(String sourceSystem, String sourceCode);
    }

    public interface DdiRuleRepository extends JpaRepository<DdiRuleEntity, UUID> {
        List<DdiRuleEntity> findByDrugACodeIn(Collection<String> drugCodes);
    }

    private final CodeConceptRepository conceptRepo;
    private final ConceptClosureRepository closureRepo;
    private final ConceptMapRepository mapRepo;
    private final DdiRuleRepository ddiRepo;

    public TerminologyService(
            CodeConceptRepository conceptRepo,
            ConceptClosureRepository closureRepo,
            ConceptMapRepository mapRepo,
            DdiRuleRepository ddiRepo) {
        this.conceptRepo = conceptRepo;
        this.closureRepo = closureRepo;
        this.mapRepo = mapRepo;
        this.ddiRepo = ddiRepo;
    }

    @Cacheable(cacheNames = "terminologyConcepts", key = "#systemUri + ':' + #code")
    @Transactional(readOnly = true)
    public Optional<CodeConceptEntity> lookup(String systemUri, String code) {
        return conceptRepo.findBySystemUriAndCode(systemUri, code);
    }

    @Transactional(readOnly = true)
    public List<CodeConceptEntity> search(String systemUri, String text, int limit) {
        return conceptRepo.findBySystemUriAndDisplayEnContainingIgnoreCase(
                systemUri, text, PageRequest.of(0, Math.min(limit, 50)));
    }

    /** O(1) SNOMED CT / ATC IS-A hierarchy subsumption check backed by Caffeine L1 cache. */
    @Cacheable(cacheNames = "snomedClosure", key = "#systemUri + ':' + #ancestorCode + ':' + #descendantCode")
    @Transactional(readOnly = true)
    public boolean isSubsumedBy(String systemUri, String ancestorCode, String descendantCode) {
        if (Objects.equals(ancestorCode, descendantCode)) return true;
        return closureRepo.existsBySystemUriAndAncestorCodeAndDescendantCode(systemUri, ancestorCode, descendantCode);
    }

    @Transactional(readOnly = true)
    public List<ConceptMapEntity> translate(String sourceSystem, String sourceCode) {
        return mapRepo.findBySourceSystemAndSourceCode(sourceSystem, sourceCode);
    }

    /** Deterministic UCUM clinical unit converter used by CDSS and LIS auto-verification. */
    public BigDecimal convertUcum(BigDecimal value, String fromUnit, String toUnit) {
        if (fromUnit.equalsIgnoreCase(toUnit)) return value;
        if ("Cel".equalsIgnoreCase(fromUnit) && "[degF]".equalsIgnoreCase(toUnit)) {
            return value.multiply(new BigDecimal("1.8")).add(new BigDecimal("32")).setScale(2, RoundingMode.HALF_UP);
        }
        if ("[degF]".equalsIgnoreCase(fromUnit) && "Cel".equalsIgnoreCase(toUnit)) {
            return value.subtract(new BigDecimal("32")).divide(new BigDecimal("1.8"), 2, RoundingMode.HALF_UP);
        }
        if ("mg/dL".equalsIgnoreCase(fromUnit) && "mmol/L".equalsIgnoreCase(toUnit)) {
            // Glucose conversion factor 18.0182 mg/dL per mmol/L
            return value.divide(new BigDecimal("18.0182"), 2, RoundingMode.HALF_UP);
        }
        if ("mmol/L".equalsIgnoreCase(fromUnit) && "mg/dL".equalsIgnoreCase(toUnit)) {
            return value.multiply(new BigDecimal("18.0182")).setScale(1, RoundingMode.HALF_UP);
        }
        if ("[lb_av]".equalsIgnoreCase(fromUnit) && "kg".equalsIgnoreCase(toUnit)) {
            return value.multiply(new BigDecimal("0.45359237")).setScale(2, RoundingMode.HALF_UP);
        }
        throw new IllegalArgumentException("Unsupported UCUM unit conversion: " + fromUnit + " -> " + toUnit);
    }

    /**
     * Seeds essential LOINC vitals/labs, ICD-10 diagnoses (Malaria, Hypertension, Diabetes, HIV,
     * Dental Caries, Cataract), SNOMED CT drug-allergy classes, and DDI safety rules on first boot.
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (conceptRepo.count() > 0) return;

        seedConcept(LOINC, "8310-5", "Body temperature", "Température corporelle", "VITALS", "Cel");
        seedConcept(LOINC, "8867-4", "Heart rate", "Fréquence cardiaque", "VITALS", "/min");
        seedConcept(LOINC, "9279-1", "Respiratory rate", "Fréquence respiratoire", "VITALS", "/min");
        seedConcept(LOINC, "8480-6", "Systolic blood pressure", "Pression artérielle systolique", "VITALS", "mm[Hg]");
        seedConcept(LOINC, "8462-4", "Diastolic blood pressure", "Pression artérielle diastolique", "VITALS", "mm[Hg]");
        seedConcept(LOINC, "2708-6", "Oxygen saturation in Arterial blood", "Saturation en oxygène (SpO2)", "VITALS", "%");
        seedConcept(LOINC, "29463-7", "Body weight", "Poids corporel", "VITALS", "kg");
        seedConcept(LOINC, "8302-2", "Body height", "Taille", "VITALS", "cm");
        seedConcept(LOINC, "2339-0", "Glucose [Mass/volume] in Blood", "Glycémie capillaire/veineuse", "LAB", "mg/dL");
        seedConcept(LOINC, "718-7", "Hemoglobin [Mass/volume] in Blood", "Hémoglobine", "LAB", "g/dL");
        seedConcept(LOINC, "2823-3", "Potassium [Moles/volume] in Serum", "Kaliémie", "LAB", "mmol/L");
        seedConcept(LOINC, "70651-5", "Plasmodium falciparum Ag [Presence] Rapid", "TDR Paludisme", "LAB", null);

        seedConcept(ICD10, "B50.9", "Plasmodium falciparum malaria, unspecified", "Paludisme à P. falciparum", "INFECTIOUS", null);
        seedConcept(ICD10, "B20", "Human immunodeficiency virus [HIV] disease", "Infection par le VIH", "INFECTIOUS", null);
        seedConcept(ICD10, "B18.1", "Chronic viral hepatitis B without delta-agent", "Hépatite B chronique", "INFECTIOUS", null);
        seedConcept(ICD10, "I10", "Essential (primary) hypertension", "Hypertension artérielle essentielle", "CARDIO", null);
        seedConcept(ICD10, "E11.9", "Type 2 diabetes mellitus without complications", "Diabète de type 2", "ENDOCRINE", null);
        seedConcept(ICD10, "K02.9", "Dental caries, unspecified", "Carie dentaire", "DENTAL", null);
        seedConcept(ICD10, "H26.9", "Cataract, unspecified", "Cataracte", "OPHTHALMOLOGY", null);

        seedConcept(SNOMED, "764146007", "Penicillin (substance)", "Pénicilline", "ALLERGY", null);
        seedConcept(RXNORM, "723", "Amoxicillin 500 MG Oral Capsule", "Amoxicilline 500 mg", "MEDICATION", "mg");
        seedConcept(RXNORM, "11289", "Warfarin Sodium", "Warfarine", "MEDICATION", "mg");
        seedConcept(RXNORM, "5640", "Ibuprofen 400 MG Oral Tablet", "Ibuprofène 400 mg", "MEDICATION", "mg");
        seedConcept(RXNORM, "6809", "Metformin 500 MG Oral Tablet", "Metformine 500 mg", "MEDICATION", "mg");
        seedConcept(RXNORM, "161", "Acetaminophen / Paracetamol", "Paracétamol", "MEDICATION", "mg");

        // Penicillin class subsumption: Penicillin allergy -> blocks Amoxicillin
        ConceptClosureEntity penicillinClosure = new ConceptClosureEntity();
        penicillinClosure.setSystemUri(SNOMED);
        penicillinClosure.setAncestorCode("764146007");
        penicillinClosure.setDescendantCode("723");
        penicillinClosure.setDepth(1);
        closureRepo.save(penicillinClosure);

        // Critical clinical safety DDI rules
        seedDdi("11289", "5640", "DDI", "CRITICAL",
                "Major bleeding risk: Warfarin + NSAID (Ibuprofen) significantly increases GI hemorrhage risk.",
                "Substitute Paracetamol (RxNorm 161) or monitor INR closely.");
        seedDdi("723", "764146007", "DRUG_ALLERGY_CLASS", "CRITICAL",
                "Beta-lactam cross-reactivity alert: Amoxicillin prescribed to patient with Penicillin allergy.",
                "Select macrolide/non-beta-lactam alternative.");
        seedDdi("6809", "N18.4", "CONTRAINDICATION", "CRITICAL",
                "Lactic acidosis risk: Metformin contraindicated when eGFR < 30 mL/min (CKD Stage 4/5).",
                "Hold Metformin and evaluate insulin or DPP-4 inhibitor.");
    }

    /**
     * Bulk-imports standardized clinical concepts from CSV, SNOMED CT RF2 tab-delimited text,
     * or HL7 FHIR R4 {@code CodeSystem} JSON payloads into {@code hc_terminology_concepts}.
     *
     * <p>Supported formats:</p>
     * <ul>
     *   <li>{@code CSV}: {@code systemUri,code,displayEn,displayFr,clinicalDomain,ucumUnit}</li>
     *   <li>{@code SNOMED_RF2}: tab-delimited {@code id\teffectiveTime\tactive\tmoduleId\tconceptId\tlanguageCode\ttypeId\tterm\tcaseSignificanceId}</li>
     *   <li>{@code FHIR_CODESYSTEM}: FHIR R4 JSON {@code {"url":"...", "concept":[{"code":"...", "display":"..."}]}}</li>
     * </ul>
     */
    @Transactional
    public Map<String, Object> bulkImportConcepts(String format, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Import content cannot be blank");
        }
        String fmt = format == null ? "CSV" : format.trim().toUpperCase(Locale.ROOT);
        int imported = 0;
        int skipped = 0;

        switch (fmt) {
            case "CSV" -> {
                String[] lines = content.split("\\r?\\n");
                for (String rawLine : lines) {
                    String line = rawLine.trim();
                    if (line.isEmpty() || line.startsWith("#") || line.toLowerCase(Locale.ROOT).startsWith("systemuri,")) {
                        continue;
                    }
                    String[] cols = line.split(",", -1);
                    if (cols.length < 3) {
                        skipped++;
                        continue;
                    }
                    String sys = cols[0].trim();
                    String code = cols[1].trim();
                    String en = cols[2].trim();
                    String fr = cols.length > 3 && !cols[3].isBlank() ? cols[3].trim() : en;
                    String dom = cols.length > 4 && !cols[4].isBlank() ? cols[4].trim() : "GENERAL";
                    String ucum = cols.length > 5 && !cols[5].isBlank() ? cols[5].trim() : null;
                    if (conceptRepo.findBySystemUriAndCode(sys, code).isEmpty()) {
                        seedConcept(sys, code, en, fr, dom, ucum);
                        imported++;
                    } else {
                        skipped++;
                    }
                }
            }
            case "SNOMED_RF2" -> {
                String[] lines = content.split("\\r?\\n");
                for (String rawLine : lines) {
                    String line = rawLine.trim();
                    if (line.isEmpty() || line.startsWith("id\t")) {
                        continue;
                    }
                    String[] cols = line.split("\\t", -1);
                    // RF2 Description snapshot: active=1 at index 2, conceptId at 4, term at 7
                    if (cols.length < 8 || !"1".equals(cols[2].trim())) {
                        skipped++;
                        continue;
                    }
                    String conceptId = cols[4].trim();
                    String term = cols[7].trim();
                    if (conceptRepo.findBySystemUriAndCode(SNOMED, conceptId).isEmpty()) {
                        seedConcept(SNOMED, conceptId, term, term, "SNOMED_RF2", null);
                        imported++;
                    } else {
                        skipped++;
                    }
                }
            }
            case "FHIR_CODESYSTEM" -> {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(content);
                    String sys = root.path("url").asText(SNOMED);
                    com.fasterxml.jackson.databind.JsonNode concepts = root.path("concept");
                    if (concepts.isArray()) {
                        for (com.fasterxml.jackson.databind.JsonNode item : concepts) {
                            String code = item.path("code").asText("");
                            String display = item.path("display").asText(code);
                            if (!code.isBlank() && conceptRepo.findBySystemUriAndCode(sys, code).isEmpty()) {
                                seedConcept(sys, code, display, display, "FHIR_IMPORT", null);
                                imported++;
                            } else {
                                skipped++;
                            }
                        }
                    }
                } catch (Exception ex) {
                    throw new IllegalArgumentException("Invalid FHIR CodeSystem JSON payload: " + ex.getMessage(), ex);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported terminology import format: " + fmt);
        }

        return Map.of("format", fmt, "importedCount", imported, "skippedCount", skipped);
    }

    private void seedConcept(String sys, String code, String en, String fr, String domain, String ucum) {
        CodeConceptEntity c = new CodeConceptEntity();
        c.setSystemUri(sys);
        c.setCode(code);
        c.setDisplayEn(en);
        c.setDisplayFr(fr);
        c.setClinicalDomain(domain);
        c.setUcumUnit(ucum);
        conceptRepo.save(c);
    }

    private void seedDdi(String a, String b, String type, String sev, String msg, String action) {
        DdiRuleEntity r = new DdiRuleEntity();
        r.setDrugACode(a);
        r.setDrugBOrConditionCode(b);
        r.setRuleType(type);
        r.setSeverity(sev);
        r.setClinicalMessage(msg);
        r.setRecommendedAction(action);
        ddiRepo.save(r);
    }
}
