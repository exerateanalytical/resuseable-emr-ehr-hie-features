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

import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.terminology.domain.TerminologyEntities.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Pattern;

/**
 * High-speed L1 Caffeine-cached clinical terminology &amp; validation engine supporting:
 * <ul>
 *   <li>LOINC (vitals, lab panels with Mod-10 check-digit validation), SNOMED CT (SCTID partition &amp; IS-A closure),
 *       ICD-10/11 (WHO diagnostic structure), RxNorm, ATC, and CVX</li>
 *   <li>Strict terminology validation ({@code $validate-code}) checking code-system URI, syntactic structure,
 *       active status, bilingual display accuracy, clinical domain, and UCUM unit compatibility</li>
 *   <li>Precomputed SNOMED/ATC {@code IS-A} closure checks ({@code $subsumes}) without recursive DB queries</li>
 *   <li>Cross-terminology concept mapping ({@code $translate}, e.g., SNOMED CT clinical findings to ICD-10 billing codes)</li>
 *   <li>UCUM unit conversion ({@code mg/dL <-> mmol/L}, {@code Cel <-> [degF]}, {@code kg <-> [lb_av]}, {@code g/dL <-> g/L})</li>
 *   <li>Bulk CSV/RF2/FHIR ingestion and out-of-the-box primary/tropical/hospital starter seed pack</li>
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
    public static final String UCUM = "http://unitsofmeasure.org";

    private static final Pattern LOINC_PATTERN = Pattern.compile("^(\\d{1,7})-(\\d)$");
    private static final Pattern SNOMED_PATTERN = Pattern.compile("^\\d{6,18}$");
    private static final Pattern ICD10_PATTERN = Pattern.compile("^[A-TV-Z][0-9][0-9AB](?:\\.[0-9A-TV-Z]{1,4})?$");
    private static final Pattern ICD11_PATTERN = Pattern.compile("^[0-9A-Z]{2,8}(?:\\.[0-9A-Z]{1,4})?(?:/[0-9A-Z.]+)?$");
    private static final Pattern RXNORM_PATTERN = Pattern.compile("^\\d{1,9}$");
    private static final Pattern ATC_PATTERN = Pattern.compile("^[A-Z]\\d{2}[A-Z]{2}\\d{2}$");
    private static final Pattern CVX_PATTERN = Pattern.compile("^\\d{1,4}$");

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

    /** Metadata descriptor for a supported clinical code system. */
    public record CodeSystemDescriptor(
            String id,
            String systemUri,
            String name,
            String title,
            String version,
            String publisher,
            String syntaxRule,
            int activeConceptCount
    ) {}

    /** Request payload for single or batch clinical code validation. */
    public record CodeValidationRequest(
            String system,
            String code,
            String display,
            String expectedDomain,
            String ucumUnit,
            boolean strictDisplayMatch
    ) {
        public CodeValidationRequest(String system, String code, String display) {
            this(system, code, display, null, null, false);
        }
    }

    /** Detailed outcome of validating a coded clinical concept against LOINC, SNOMED-CT, ICD-10, etc. */
    public record CodeValidationResult(
            boolean valid,
            boolean active,
            boolean syntaxValid,
            boolean displayMatches,
            boolean domainMatches,
            boolean ucumMatches,
            String normalizedSystemUri,
            String codeSystemName,
            String code,
            String canonicalDisplayEn,
            String canonicalDisplayFr,
            String clinicalDomain,
            String expectedUcumUnit,
            String issueCode,
            String severity,
            String message
    ) {}

    /** Aggregated outcome of validating a batch of clinical codings (e.g., an Encounter or Observation panel). */
    public record BatchValidationResult(
            int totalChecked,
            int validCount,
            int invalidCount,
            boolean allValid,
            List<CodeValidationResult> results
    ) {}

    /** HL7 FHIR R4 {@code CodeSystem/$subsumes} evaluation result. */
    public record SubsumptionOutcome(
            String systemUri,
            String codeA,
            String codeB,
            String outcome
    ) {}

    private record SeedConceptSpec(
            String systemUri,
            String code,
            String displayEn,
            String displayFr,
            String clinicalDomain,
            String ucumUnit
    ) {}

    private static final List<SeedConceptSpec> CANONICAL_STARTER_CONCEPTS = List.of(
            // LOINC Vitals & Laboratory Panels
            new SeedConceptSpec(LOINC, "8310-5", "Body temperature", "Température corporelle", "VITALS", "Cel"),
            new SeedConceptSpec(LOINC, "8867-4", "Heart rate", "Fréquence cardiaque", "VITALS", "/min"),
            new SeedConceptSpec(LOINC, "9279-1", "Respiratory rate", "Fréquence respiratoire", "VITALS", "/min"),
            new SeedConceptSpec(LOINC, "85354-9", "Blood pressure panel with all children optional", "Panel de pression artérielle", "VITALS", "mm[Hg]"),
            new SeedConceptSpec(LOINC, "8480-6", "Systolic blood pressure", "Pression artérielle systolique", "VITALS", "mm[Hg]"),
            new SeedConceptSpec(LOINC, "8462-4", "Diastolic blood pressure", "Pression artérielle diastolique", "VITALS", "mm[Hg]"),
            new SeedConceptSpec(LOINC, "2708-6", "Oxygen saturation in Arterial blood", "Saturation en oxygène (SpO2)", "VITALS", "%"),
            new SeedConceptSpec(LOINC, "59408-5", "Oxygen saturation in Arterial blood by Pulse oximetry", "SpO2 par oxymétrie de pouls", "VITALS", "%"),
            new SeedConceptSpec(LOINC, "29463-7", "Body weight", "Poids corporel", "VITALS", "kg"),
            new SeedConceptSpec(LOINC, "8302-2", "Body height", "Taille", "VITALS", "cm"),
            new SeedConceptSpec(LOINC, "39156-5", "Body mass index (BMI) [Ratio]", "Indice de masse corporelle (IMC)", "VITALS", "kg/m2"),
            new SeedConceptSpec(LOINC, "2339-0", "Glucose [Mass/volume] in Blood", "Glycémie capillaire/veineuse", "LAB", "mg/dL"),
            new SeedConceptSpec(LOINC, "4548-4", "Hemoglobin A1c/Hemoglobin.total in Blood", "Hémoglobine glyquée (HbA1c)", "LAB", "%"),
            new SeedConceptSpec(LOINC, "718-7", "Hemoglobin [Mass/volume] in Blood", "Hémoglobine", "LAB", "g/dL"),
            new SeedConceptSpec(LOINC, "2823-3", "Potassium [Moles/volume] in Serum", "Kaliémie", "LAB", "mmol/L"),
            new SeedConceptSpec(LOINC, "2951-2", "Sodium [Moles/volume] in Serum or Plasma", "Natrémie", "LAB", "mmol/L"),
            new SeedConceptSpec(LOINC, "2160-0", "Creatinine [Mass/volume] in Serum or Plasma", "Créatininémie", "LAB", "mg/dL"),
            new SeedConceptSpec(LOINC, "70651-5", "Plasmodium falciparum Ag [Presence] Rapid", "TDR Paludisme", "LAB", null),

            // ICD-10 Diagnoses
            new SeedConceptSpec(ICD10, "B50.9", "Plasmodium falciparum malaria, unspecified", "Paludisme à P. falciparum", "INFECTIOUS", null),
            new SeedConceptSpec(ICD10, "B50.0", "Plasmodium falciparum malaria with cerebral complications", "Neuropaludisme à P. falciparum", "INFECTIOUS", null),
            new SeedConceptSpec(ICD10, "B20", "Human immunodeficiency virus [HIV] disease", "Infection par le VIH", "INFECTIOUS", null),
            new SeedConceptSpec(ICD10, "B18.1", "Chronic viral hepatitis B without delta-agent", "Hépatite B chronique", "INFECTIOUS", null),
            new SeedConceptSpec(ICD10, "A15.0", "Tuberculosis of lung, confirmed by sputum microscopy", "Tuberculose pulmonaire confirmée", "INFECTIOUS", null),
            new SeedConceptSpec(ICD10, "A00.9", "Cholera, unspecified", "Choléra, sans précision", "INFECTIOUS", null),
            new SeedConceptSpec(ICD10, "I10", "Essential (primary) hypertension", "Hypertension artérielle essentielle", "CARDIO", null),
            new SeedConceptSpec(ICD10, "I50.9", "Heart failure, unspecified", "Insuffisance cardiaque", "CARDIO", null),
            new SeedConceptSpec(ICD10, "E11.9", "Type 2 diabetes mellitus without complications", "Diabète de type 2", "ENDOCRINE", null),
            new SeedConceptSpec(ICD10, "J18.9", "Pneumonia, unspecified organism", "Pneumonie, sans précision", "RESPIRATORY", null),
            new SeedConceptSpec(ICD10, "N18.4", "Chronic kidney disease, stage 4 (severe)", "Maladie rénale chronique stade 4", "RENAL", null),
            new SeedConceptSpec(ICD10, "O14.1", "Severe pre-eclampsia", "Pré-éclampsie sévère", "MATERNAL", null),
            new SeedConceptSpec(ICD10, "K02.9", "Dental caries, unspecified", "Carie dentaire", "DENTAL", null),
            new SeedConceptSpec(ICD10, "H26.9", "Cataract, unspecified", "Cataracte", "OPHTHALMOLOGY", null),

            // SNOMED-CT Findings, Substances, Disorders & Procedures
            new SeedConceptSpec(SNOMED, "764146007", "Penicillin (substance)", "Pénicilline", "ALLERGY", null),
            new SeedConceptSpec(SNOMED, "372687004", "Amoxicillin (substance)", "Amoxicilline (substance)", "ALLERGY", null),
            new SeedConceptSpec(SNOMED, "38341003", "Hypertensive disorder, systemic arterial (disorder)", "Hypertension artérielle systémique", "CARDIO", null),
            new SeedConceptSpec(SNOMED, "44054006", "Diabetes mellitus type 2 (disorder)", "Diabète sucré de type 2", "ENDOCRINE", null),
            new SeedConceptSpec(SNOMED, "61462000", "Malaria (disorder)", "Paludisme (trouble)", "INFECTIOUS", null),
            new SeedConceptSpec(SNOMED, "233604007", "Pneumonia (disorder)", "Pneumonie", "RESPIRATORY", null),
            new SeedConceptSpec(SNOMED, "386661006", "Fever (finding)", "Fièvre", "FINDING", null),
            new SeedConceptSpec(SNOMED, "271807003", "Eruption of skin (finding)", "Éruption cutanée", "FINDING", null),
            new SeedConceptSpec(SNOMED, "86406008", "Human immunodeficiency virus infection (disorder)", "Infection à VIH", "INFECTIOUS", null),

            // RxNorm & ATC Medications
            new SeedConceptSpec(RXNORM, "723", "Amoxicillin 500 MG Oral Capsule", "Amoxicilline 500 mg", "MEDICATION", "mg"),
            new SeedConceptSpec(RXNORM, "11289", "Warfarin Sodium", "Warfarine", "MEDICATION", "mg"),
            new SeedConceptSpec(RXNORM, "5640", "Ibuprofen 400 MG Oral Tablet", "Ibuprofène 400 mg", "MEDICATION", "mg"),
            new SeedConceptSpec(RXNORM, "6809", "Metformin 500 MG Oral Tablet", "Metformine 500 mg", "MEDICATION", "mg"),
            new SeedConceptSpec(RXNORM, "161", "Acetaminophen / Paracetamol", "Paracétamol", "MEDICATION", "mg"),
            new SeedConceptSpec(ATC, "J01CA04", "Amoxicillin", "Amoxicilline", "MEDICATION", "mg"),
            new SeedConceptSpec(ATC, "P01BF01", "Artemether and lumefantrine", "Artéméther + Luméfantrine", "MEDICATION", "mg"),
            new SeedConceptSpec(ATC, "A10BA02", "Metformin", "Metformine", "MEDICATION", "mg")
    );

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

    /**
     * Normalizes short code-system aliases or HL7 OIDs to their canonical HL7 FHIR R4 System URI.
     *
     * @param systemOrAlias short alias (e.g., {@code "LOINC"}, {@code "SNOMED-CT"}, {@code "ICD-10"}) or URI
     * @return canonical FHIR system URI, or {@code null} if unknown
     */
    public static String normalizeSystemUri(String systemOrAlias) {
        if (systemOrAlias == null || systemOrAlias.isBlank()) {
            return null;
        }
        String cleaned = systemOrAlias.trim();
        String upper = cleaned.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "LOINC", "HTTP://LOINC.ORG", "HTTPS://LOINC.ORG", "URN:OID:2.16.840.1.113883.6.1" -> LOINC;
            case "SNOMED", "SNOMED-CT", "SNOMEDCT", "SNOMED_CT", "SCT",
                 "HTTP://SNOMED.INFO/SCT", "HTTPS://SNOMED.INFO/SCT", "URN:OID:2.16.840.1.113883.6.96" -> SNOMED;
            case "ICD-10", "ICD10", "ICD_10", "ICD-10-CM", "ICD10CM",
                 "HTTP://HL7.ORG/FHIR/SID/ICD-10", "HTTP://HL7.ORG/FHIR/SID/ICD-10-CM",
                 "URN:OID:2.16.840.1.113883.6.3" -> ICD10;
            case "ICD-11", "ICD11", "ICD_11", "HTTP://ID.WHO.INT/ICD/RELEASE/11/MMS" -> ICD11;
            case "RXNORM", "HTTP://WWW.NLM.NIH.GOV/RESEARCH/UMLS/RXNORM",
                 "URN:OID:2.16.840.1.113883.6.88" -> RXNORM;
            case "ATC", "WHO-ATC", "HTTP://WWW.WHOCC.NO/ATC" -> ATC;
            case "CVX", "HTTP://HL7.ORG/FHIR/SID/CVX" -> CVX;
            case "UCUM", "HTTP://UNITSOFMEASURE.ORG" -> UCUM;
            default -> cleaned.startsWith("http://") || cleaned.startsWith("https://") || cleaned.startsWith("urn:")
                    ? cleaned
                    : null;
        };
    }

    /** Returns the human-readable standard name for a canonical code system URI. */
    public static String resolveCodeSystemName(String systemUri) {
        if (systemUri == null) return "UNKNOWN";
        return switch (systemUri) {
            case LOINC -> "LOINC";
            case SNOMED -> "SNOMED-CT";
            case ICD10 -> "ICD-10";
            case ICD11 -> "ICD-11";
            case RXNORM -> "RxNorm";
            case ATC -> "WHO-ATC";
            case CVX -> "CDC-CVX";
            case UCUM -> "UCUM";
            default -> "CUSTOM";
        };
    }

    /**
     * Validates LOINC code syntax ({@code ^\d{1,7}-\d$}) AND verifies the official Regenstrief
     * Mod-10 (Luhn algorithm) check digit following the hyphen.
     */
    public static boolean isValidLoincSyntax(String code) {
        if (code == null) return false;
        var matcher = LOINC_PATTERN.matcher(code.trim());
        if (!matcher.matches()) {
            return false;
        }
        String baseDigits = matcher.group(1);
        int providedCheckDigit = matcher.group(2).charAt(0) - '0';
        return computeLoincMod10CheckDigit(baseDigits) == providedCheckDigit;
    }

    /**
     * Computes the Regenstrief LOINC Mod-10 check digit for the numeric prefix before the hyphen.
     */
    public static int computeLoincMod10CheckDigit(String numericPrefix) {
        if (numericPrefix == null || numericPrefix.isEmpty()) {
            throw new IllegalArgumentException("LOINC numeric prefix is required");
        }
        int oddPosFromRightSum = 0;
        StringBuilder oddConcat = new StringBuilder();
        int evenPosFromRightSum = 0;
        boolean doubleDigit = true;
        for (int i = numericPrefix.length() - 1; i >= 0; i--) {
            char ch = numericPrefix.charAt(i);
            if (!Character.isDigit(ch)) {
                throw new IllegalArgumentException("Non-digit character in LOINC prefix: " + numericPrefix);
            }
            if (doubleDigit) {
                oddConcat.insert(0, ch);
            } else {
                evenPosFromRightSum += (ch - '0');
            }
            doubleDigit = !doubleDigit;
        }
        long doubledOdd = Long.parseLong(oddConcat.toString()) * 2L;
        for (char c : Long.toString(doubledOdd).toCharArray()) {
            oddPosFromRightSum += (c - '0');
        }
        int total = oddPosFromRightSum + evenPosFromRightSum;
        int nextMultipleOfTen = ((total + 9) / 10) * 10;
        return nextMultipleOfTen - total;
    }

    /**
     * Validates SNOMED-CT Concept ID (SCTID) structure: 6 to 18 digits, non-leading-zero,
     * and valid concept partition identifier ({@code 00} standard concept or {@code 10} extension concept).
     */
    public static boolean isValidSnomedSyntax(String code) {
        if (code == null) return false;
        String trimmed = code.trim();
        if (!SNOMED_PATTERN.matcher(trimmed).matches() || trimmed.charAt(0) == '0') {
            return false;
        }
        int len = trimmed.length();
        String partitionId = trimmed.substring(len - 3, len - 1);
        return "00".equals(partitionId) || "10".equals(partitionId);
    }

    /**
     * Validates WHO ICD-10 diagnostic code syntax (e.g. {@code I10}, {@code B50.9}, {@code E11.9}, {@code A15.0}).
     */
    public static boolean isValidIcd10Syntax(String code) {
        if (code == null) return false;
        return ICD10_PATTERN.matcher(code.trim().toUpperCase(Locale.ROOT)).matches();
    }

    /**
     * Checks whether a code conforms to the syntactic rules of its normalized code system URI.
     */
    public static boolean isValidCodeSyntaxForSystem(String normalizedSystemUri, String code) {
        if (normalizedSystemUri == null || code == null || code.isBlank()) {
            return false;
        }
        String trimmed = code.trim();
        return switch (normalizedSystemUri) {
            case LOINC -> isValidLoincSyntax(trimmed);
            case SNOMED -> isValidSnomedSyntax(trimmed);
            case ICD10 -> isValidIcd10Syntax(trimmed);
            case ICD11 -> ICD11_PATTERN.matcher(trimmed).matches();
            case RXNORM -> RXNORM_PATTERN.matcher(trimmed).matches();
            case ATC -> ATC_PATTERN.matcher(trimmed.toUpperCase(Locale.ROOT)).matches();
            case CVX -> CVX_PATTERN.matcher(trimmed).matches();
            default -> !trimmed.isEmpty() && trimmed.length() <= 64;
        };
    }

    @Cacheable(cacheNames = "terminologyConcepts", key = "#systemUri + ':' + #code")
    @Transactional(readOnly = true)
    public Optional<CodeConceptEntity> lookup(String systemUri, String code) {
        String normalizedSystem = normalizeSystemUri(systemUri);
        String targetSystem = normalizedSystem != null ? normalizedSystem : systemUri;
        String trimmedCode = code != null ? code.trim() : "";
        if (conceptRepo != null) {
            Optional<CodeConceptEntity> fromDb = conceptRepo.findBySystemUriAndCode(targetSystem, trimmedCode);
            if (fromDb.isPresent()) {
                return fromDb;
            }
            if (ICD10.equals(targetSystem) || ATC.equals(targetSystem)) {
                Optional<CodeConceptEntity> upperDb = conceptRepo.findBySystemUriAndCode(
                        targetSystem, trimmedCode.toUpperCase(Locale.ROOT));
                if (upperDb.isPresent()) {
                    return upperDb;
                }
            }
        }
        return findInCanonicalCatalog(targetSystem, trimmedCode);
    }

    @Transactional(readOnly = true)
    public List<CodeConceptEntity> search(String systemUri, String text, int limit) {
        String normalizedSystem = normalizeSystemUri(systemUri);
        String targetSystem = normalizedSystem != null ? normalizedSystem : systemUri;
        String query = text == null ? "" : text.trim();
        int maxResults = Math.min(Math.max(limit, 1), 50);

        List<CodeConceptEntity> dbMatches = conceptRepo != null
                ? conceptRepo.findBySystemUriAndDisplayEnContainingIgnoreCase(
                        targetSystem, query, PageRequest.of(0, maxResults))
                : List.of();
        if (!dbMatches.isEmpty()) {
            return dbMatches;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        return CANONICAL_STARTER_CONCEPTS.stream()
                .filter(s -> Objects.equals(s.systemUri(), targetSystem))
                .filter(s -> needle.isEmpty()
                        || s.code().toLowerCase(Locale.ROOT).contains(needle)
                        || s.displayEn().toLowerCase(Locale.ROOT).contains(needle)
                        || (s.displayFr() != null && s.displayFr().toLowerCase(Locale.ROOT).contains(needle)))
                .limit(maxResults)
                .map(TerminologyService::toEntity)
                .toList();
    }

    /**
     * Validates a clinical concept code against standard code systems (LOINC, SNOMED-CT, ICD-10,
     * ICD-11, RxNorm, ATC, CVX) to ensure clinical data accuracy prior to persisting FHIR resources.
     */
    @Transactional(readOnly = true)
    @ClinicalAudit(action = "TERMINOLOGY_VALIDATE_CODE", resourceType = "CodeSystem")
    public CodeValidationResult validateCode(String system, String code, String display) {
        return validateCode(new CodeValidationRequest(system, code, display, null, null, false));
    }

    /**
     * Full multi-stage clinical terminology validator:
     * <ol>
     *   <li>Normalizes and verifies the code system URI/alias ({@code LOINC}, {@code SNOMED-CT}, {@code ICD-10}, etc.)</li>
     *   <li>Verifies structural code syntax (LOINC Mod-10 check digit, SNOMED SCTID partition, ICD-10 format)</li>
     *   <li>Resolves the concept in PostgreSQL {@code hc_code_systems} (with canonical catalog fallback)</li>
     *   <li>Checks concept lifecycle status ({@code active == true})</li>
     *   <li>Verifies display term accuracy (English or French) and optional clinical domain &amp; UCUM unit compatibility</li>
     * </ol>
     */
    @Transactional(readOnly = true)
    @ClinicalAudit(action = "TERMINOLOGY_VALIDATE_CODE", resourceType = "CodeSystem")
    public CodeValidationResult validateCode(CodeValidationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("CodeValidationRequest must not be null");
        }
        String normalizedSystem = normalizeSystemUri(request.system());
        if (normalizedSystem == null || !Set.of(LOINC, SNOMED, ICD10, ICD11, RXNORM, ATC, CVX).contains(normalizedSystem)) {
            return new CodeValidationResult(
                    false, false, false, false, false, false,
                    normalizedSystem != null ? normalizedSystem : Objects.toString(request.system(), ""),
                    resolveCodeSystemName(normalizedSystem),
                    Objects.toString(request.code(), ""),
                    null, null, null, null,
                    "UNKNOWN_CODE_SYSTEM",
                    "error",
                    "Unsupported or unrecognized clinical terminology system: '" + request.system()
                            + "'. Supported systems: LOINC, SNOMED-CT, ICD-10, ICD-11, RxNorm, ATC, CVX."
            );
        }

        String systemName = resolveCodeSystemName(normalizedSystem);
        String rawCode = request.code() == null ? "" : request.code().trim();
        if (rawCode.isEmpty()) {
            return new CodeValidationResult(
                    false, false, false, false, false, false,
                    normalizedSystem, systemName, "",
                    null, null, null, null,
                    "BLANK_CODE",
                    "error",
                    "Concept code must not be blank for code system " + systemName + " (" + normalizedSystem + ")."
            );
        }

        boolean syntaxOk = isValidCodeSyntaxForSystem(normalizedSystem, rawCode);
        if (!syntaxOk) {
            return new CodeValidationResult(
                    false, false, false, false, false, false,
                    normalizedSystem, systemName, rawCode,
                    null, null, null, null,
                    "INVALID_CODE_SYNTAX",
                    "error",
                    buildSyntaxErrorMessage(normalizedSystem, systemName, rawCode)
            );
        }

        Optional<CodeConceptEntity> foundOpt = lookup(normalizedSystem, rawCode);
        if (foundOpt.isEmpty()) {
            return new CodeValidationResult(
                    false, false, true, false, false, false,
                    normalizedSystem, systemName, rawCode,
                    null, null, null, null,
                    "CODE_NOT_FOUND",
                    "error",
                    "Code '" + rawCode + "' was not found in active " + systemName + " (" + normalizedSystem + ") dictionary."
            );
        }

        CodeConceptEntity concept = foundOpt.get();
        if (!concept.isActive()) {
            return new CodeValidationResult(
                    false, false, true, false, true, true,
                    normalizedSystem, systemName, concept.getCode(),
                    concept.getDisplayEn(), concept.getDisplayFr(),
                    concept.getClinicalDomain(), concept.getUcumUnit(),
                    "INACTIVE_CONCEPT",
                    "error",
                    "Code '" + concept.getCode() + "' (" + concept.getDisplayEn() + ") is deprecated/inactive in " + systemName + "."
            );
        }

        boolean displayMatches = true;
        if (request.display() != null && !request.display().isBlank()) {
            String given = request.display().trim();
            displayMatches = given.equalsIgnoreCase(concept.getDisplayEn())
                    || (concept.getDisplayFr() != null && given.equalsIgnoreCase(concept.getDisplayFr()))
                    || concept.getDisplayEn().toLowerCase(Locale.ROOT).contains(given.toLowerCase(Locale.ROOT));
        }

        boolean domainMatches = true;
        if (request.expectedDomain() != null && !request.expectedDomain().isBlank() && concept.getClinicalDomain() != null) {
            domainMatches = request.expectedDomain().trim().equalsIgnoreCase(concept.getClinicalDomain());
        }

        boolean ucumMatches = true;
        if (request.ucumUnit() != null && !request.ucumUnit().isBlank() && concept.getUcumUnit() != null) {
            ucumMatches = areUcumUnitsCompatible(concept.getUcumUnit(), request.ucumUnit().trim());
        }

        if (!domainMatches) {
            return new CodeValidationResult(
                    false, true, true, displayMatches, false, ucumMatches,
                    normalizedSystem, systemName, concept.getCode(),
                    concept.getDisplayEn(), concept.getDisplayFr(),
                    concept.getClinicalDomain(), concept.getUcumUnit(),
                    "CLINICAL_DOMAIN_MISMATCH",
                    "error",
                    "Code '" + concept.getCode() + "' belongs to domain '" + concept.getClinicalDomain()
                            + "' rather than expected domain '" + request.expectedDomain() + "'."
            );
        }

        if (!ucumMatches) {
            return new CodeValidationResult(
                    false, true, true, displayMatches, true, false,
                    normalizedSystem, systemName, concept.getCode(),
                    concept.getDisplayEn(), concept.getDisplayFr(),
                    concept.getClinicalDomain(), concept.getUcumUnit(),
                    "UCUM_UNIT_INCOMPATIBLE",
                    "error",
                    "Incompatible UCUM unit '" + request.ucumUnit() + "' for " + systemName + " '" + concept.getCode()
                            + "' (" + concept.getDisplayEn() + "); expected '" + concept.getUcumUnit() + "' or convertible equivalent."
            );
        }

        if (!displayMatches && request.strictDisplayMatch()) {
            return new CodeValidationResult(
                    false, true, true, false, true, true,
                    normalizedSystem, systemName, concept.getCode(),
                    concept.getDisplayEn(), concept.getDisplayFr(),
                    concept.getClinicalDomain(), concept.getUcumUnit(),
                    "DISPLAY_MISMATCH",
                    "error",
                    "Provided display '" + request.display() + "' does not match canonical " + systemName
                            + " display '" + concept.getDisplayEn() + "'."
            );
        }

        String issueCode = displayMatches ? "VALID" : "DISPLAY_WARNING";
        String severity = displayMatches ? "information" : "warning";
        String message = displayMatches
                ? "Validated " + systemName + " code '" + concept.getCode() + "' (" + concept.getDisplayEn() + ")."
                : "Code '" + concept.getCode() + "' is valid in " + systemName + ", but provided display '"
                        + request.display() + "' differs from canonical display '" + concept.getDisplayEn() + "'.";

        return new CodeValidationResult(
                true, true, true, displayMatches, true, true,
                normalizedSystem, systemName, concept.getCode(),
                concept.getDisplayEn(), concept.getDisplayFr(),
                concept.getClinicalDomain(), concept.getUcumUnit(),
                issueCode, severity, message
        );
    }

    /**
     * Validates a batch of clinical codes across LOINC, SNOMED-CT, ICD-10, and RxNorm in a single transaction.
     */
    @Transactional(readOnly = true)
    @ClinicalAudit(action = "TERMINOLOGY_VALIDATE_BATCH", resourceType = "CodeSystem")
    public BatchValidationResult validateBatch(List<CodeValidationRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("Batch terminology validation request list cannot be empty");
        }
        List<CodeValidationResult> outcomes = new ArrayList<>(requests.size());
        int validCount = 0;
        for (CodeValidationRequest req : requests) {
            CodeValidationResult result = validateCode(req);
            outcomes.add(result);
            if (result.valid()) {
                validCount++;
            }
        }
        int invalidCount = outcomes.size() - validCount;
        return new BatchValidationResult(outcomes.size(), validCount, invalidCount, invalidCount == 0, List.copyOf(outcomes));
    }

    /** O(1) SNOMED CT / ATC IS-A hierarchy subsumption check backed by Caffeine L1 cache. */
    @Cacheable(cacheNames = "snomedClosure", key = "#systemUri + ':' + #ancestorCode + ':' + #descendantCode")
    @Transactional(readOnly = true)
    public boolean isSubsumedBy(String systemUri, String ancestorCode, String descendantCode) {
        String normalizedSystem = normalizeSystemUri(systemUri);
        String targetSystem = normalizedSystem != null ? normalizedSystem : systemUri;
        if (Objects.equals(ancestorCode, descendantCode)) return true;
        if (closureRepo != null
                && closureRepo.existsBySystemUriAndAncestorCodeAndDescendantCode(targetSystem, ancestorCode, descendantCode)) {
            return true;
        }
        return SNOMED.equals(targetSystem)
                && "764146007".equals(ancestorCode)
                && ("723".equals(descendantCode) || "372687004".equals(descendantCode));
    }

    /**
     * Evaluates the HL7 FHIR R4 {@code CodeSystem/$subsumes} relationship between {@code codeA} and {@code codeB}.
     *
     * @return {@code equivalent}, {@code subsumes} (A is ancestor of B), {@code subsumed-by} (A is descendant of B), or {@code not-subsumed}
     */
    @Transactional(readOnly = true)
    @ClinicalAudit(action = "TERMINOLOGY_SUBSUMES", resourceType = "CodeSystem")
    public SubsumptionOutcome evaluateSubsumption(String systemUri, String codeA, String codeB) {
        String normalized = normalizeSystemUri(systemUri);
        if (normalized == null) {
            throw new IllegalArgumentException("Unsupported terminology system for $subsumes: " + systemUri);
        }
        if (codeA == null || codeA.isBlank() || codeB == null || codeB.isBlank()) {
            throw new IllegalArgumentException("Both codeA and codeB are required for $subsumes evaluation");
        }
        String a = codeA.trim();
        String b = codeB.trim();
        String outcome;
        if (a.equals(b)) {
            outcome = "equivalent";
        } else if (isSubsumedBy(normalized, a, b)) {
            outcome = "subsumes";
        } else if (isSubsumedBy(normalized, b, a)) {
            outcome = "subsumed-by";
        } else {
            outcome = "not-subsumed";
        }
        return new SubsumptionOutcome(normalized, a, b, outcome);
    }

    @Transactional(readOnly = true)
    public List<ConceptMapEntity> translate(String sourceSystem, String sourceCode) {
        String normalizedSource = normalizeSystemUri(sourceSystem);
        String targetSource = normalizedSource != null ? normalizedSource : sourceSystem;
        String trimmedCode = sourceCode == null ? "" : sourceCode.trim();
        List<ConceptMapEntity> dbMaps = mapRepo != null
                ? mapRepo.findBySourceSystemAndSourceCode(targetSource, trimmedCode)
                : List.of();
        if (!dbMaps.isEmpty()) {
            return dbMaps;
        }
        return findBuiltInTranslations(targetSource, trimmedCode);
    }

    /**
     * Registers a new concept or updates an existing concept in {@code hc_code_systems} after syntax validation.
     */
    @Transactional
    @CacheEvict(cacheNames = {"terminologyConcepts", "snomedClosure"}, allEntries = true)
    @ClinicalAudit(action = "TERMINOLOGY_CONCEPT_UPSERT", resourceType = "CodeSystem")
    public CodeConceptEntity registerOrUpdateConcept(
            String system,
            String code,
            String displayEn,
            String displayFr,
            String clinicalDomain,
            String ucumUnit) {
        String normalizedSystem = normalizeSystemUri(system);
        if (normalizedSystem == null) {
            throw new IllegalArgumentException("Unknown or unsupported code system: " + system);
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Concept code is required");
        }
        if (displayEn == null || displayEn.isBlank()) {
            throw new IllegalArgumentException("English display name (displayEn) is required");
        }
        String trimmedCode = code.trim();
        if (!isValidCodeSyntaxForSystem(normalizedSystem, trimmedCode)) {
            throw new IllegalArgumentException(
                    buildSyntaxErrorMessage(normalizedSystem, resolveCodeSystemName(normalizedSystem), trimmedCode));
        }
        CodeConceptEntity entity = conceptRepo != null
                ? conceptRepo.findBySystemUriAndCode(normalizedSystem, trimmedCode).orElseGet(CodeConceptEntity::new)
                : new CodeConceptEntity();
        entity.setSystemUri(normalizedSystem);
        entity.setCode(trimmedCode);
        entity.setDisplayEn(displayEn.trim());
        entity.setDisplayFr(displayFr != null && !displayFr.isBlank() ? displayFr.trim() : displayEn.trim());
        entity.setClinicalDomain(clinicalDomain != null && !clinicalDomain.isBlank() ? clinicalDomain.trim().toUpperCase(Locale.ROOT) : "GENERAL");
        entity.setUcumUnit(ucumUnit != null && !ucumUnit.isBlank() ? ucumUnit.trim() : null);
        entity.setActive(true);
        return conceptRepo != null ? conceptRepo.save(entity) : entity;
    }

    /**
     * Marks a clinical concept as inactive/deprecated so subsequent {@link #validateCode} calls flag {@code INACTIVE_CONCEPT}.
     */
    @Transactional
    @CacheEvict(cacheNames = {"terminologyConcepts", "snomedClosure"}, allEntries = true)
    @ClinicalAudit(action = "TERMINOLOGY_CONCEPT_DEACTIVATE", resourceType = "CodeSystem")
    public CodeConceptEntity deactivateConcept(String system, String code) {
        String normalizedSystem = normalizeSystemUri(system);
        String targetSystem = normalizedSystem != null ? normalizedSystem : system;
        CodeConceptEntity entity = lookup(targetSystem, code)
                .orElseThrow(() -> new NoSuchElementException("Concept not found: " + targetSystem + "|" + code));
        entity.setActive(false);
        return conceptRepo != null ? conceptRepo.save(entity) : entity;
    }

    /**
     * Lists all supported standard clinical code systems with metadata and active concept counts.
     */
    @Transactional(readOnly = true)
    public List<CodeSystemDescriptor> listSupportedCodeSystems() {
        List<CodeConceptEntity> allPersisted = conceptRepo != null ? conceptRepo.findAll() : List.of();
        return List.of(
                new CodeSystemDescriptor("loinc", LOINC, "LOINC",
                        "Logical Observation Identifiers Names and Codes (Regenstrief Institute)",
                        "2.78", "Regenstrief Institute, Inc.", "^\\d{1,7}-\\d$ (Mod-10 Luhn Check Digit)",
                        countActiveForSystem(LOINC, allPersisted)),
                new CodeSystemDescriptor("snomed-ct", SNOMED, "SNOMED-CT",
                        "Systematized Nomenclature of Medicine — Clinical Terms (International Edition)",
                        "2026-03", "SNOMED International", "^\\d{6,18}$ (Partition 00/10 SCTID)",
                        countActiveForSystem(SNOMED, allPersisted)),
                new CodeSystemDescriptor("icd-10", ICD10, "ICD-10",
                        "WHO International Statistical Classification of Diseases and Related Health Problems 10th Revision",
                        "2019-WHO", "World Health Organization (WHO)", "^[A-TV-Z][0-9][0-9AB](\\.[0-9A-TV-Z]{1,4})?$",
                        countActiveForSystem(ICD10, allPersisted)),
                new CodeSystemDescriptor("icd-11", ICD11, "ICD-11",
                        "WHO International Classification of Diseases 11th Revision (MMS)",
                        "2026-01", "World Health Organization (WHO)", "^[0-9A-Z]{2,8}(\\.[0-9A-Z]{1,4})?$",
                        countActiveForSystem(ICD11, allPersisted)),
                new CodeSystemDescriptor("rxnorm", RXNORM, "RxNorm",
                        "Normalized Names for Clinical Drugs (U.S. National Library of Medicine)",
                        "2026-03", "National Library of Medicine (NLM)", "^\\d{1,9}$",
                        countActiveForSystem(RXNORM, allPersisted)),
                new CodeSystemDescriptor("atc", ATC, "WHO-ATC",
                        "WHO Anatomical Therapeutic Chemical Classification System",
                        "2026", "WHO Collaborating Centre for Drug Statistics Methodology", "^[A-Z]\\d{2}[A-Z]{2}\\d{2}$",
                        countActiveForSystem(ATC, allPersisted)),
                new CodeSystemDescriptor("cvx", CVX, "CDC-CVX",
                        "Vaccine Administered Code Set (CVX)",
                        "2026", "CDC National Center for Immunization and Respiratory Diseases", "^\\d{1,4}$",
                        countActiveForSystem(CVX, allPersisted))
        );
    }

    /** Deterministic UCUM clinical unit converter used by CDSS and LIS auto-verification. */
    public BigDecimal convertUcum(BigDecimal value, String fromUnit, String toUnit) {
        if (value == null || fromUnit == null || toUnit == null) {
            throw new IllegalArgumentException("value, fromUnit, and toUnit are required for UCUM conversion");
        }
        if (fromUnit.equalsIgnoreCase(toUnit)) return value;
        if ("mg".equalsIgnoreCase(fromUnit) && "g".equalsIgnoreCase(toUnit)) {
            return value.divide(new BigDecimal("1000"));
        }
        if ("g".equalsIgnoreCase(fromUnit) && "mg".equalsIgnoreCase(toUnit)) {
            return value.multiply(new BigDecimal("1000"));
        }
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
        if ("kg".equalsIgnoreCase(fromUnit) && "[lb_av]".equalsIgnoreCase(toUnit)) {
            return value.divide(new BigDecimal("0.45359237"), 2, RoundingMode.HALF_UP);
        }
        if ("g/dL".equalsIgnoreCase(fromUnit) && "g/L".equalsIgnoreCase(toUnit)) {
            return value.multiply(new BigDecimal("10")).setScale(1, RoundingMode.HALF_UP);
        }
        if ("g/L".equalsIgnoreCase(fromUnit) && "g/dL".equalsIgnoreCase(toUnit)) {
            return value.divide(new BigDecimal("10"), 2, RoundingMode.HALF_UP);
        }
        throw new IllegalArgumentException("Unsupported UCUM unit conversion: " + fromUnit + " -> " + toUnit);
    }

    /**
     * Seeds essential LOINC vitals/labs, ICD-10 diagnoses (Malaria, Hypertension, Diabetes, HIV,
     * Dental Caries, Cataract), SNOMED CT findings &amp; drug-allergy classes, SNOMED-to-ICD-10 ConceptMaps,
     * and DDI safety rules on first boot.
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (conceptRepo.count() > 0) return;

        for (SeedConceptSpec spec : CANONICAL_STARTER_CONCEPTS) {
            seedConcept(spec.systemUri(), spec.code(), spec.displayEn(), spec.displayFr(), spec.clinicalDomain(), spec.ucumUnit());
        }

        // Penicillin class subsumption: Penicillin allergy -> blocks Amoxicillin (RxNorm 723 & SNOMED 372687004)
        saveClosureEdge(SNOMED, "764146007", "723", 1);
        saveClosureEdge(SNOMED, "764146007", "372687004", 1);

        // Standard SNOMED-CT clinical finding -> WHO ICD-10 billing/surveillance crosswalks
        if (mapRepo != null) {
            saveConceptMap(SNOMED, "38341003", ICD10, "I10", "equivalent");
            saveConceptMap(SNOMED, "44054006", ICD10, "E11.9", "equivalent");
            saveConceptMap(SNOMED, "61462000", ICD10, "B50.9", "narrower");
            saveConceptMap(SNOMED, "233604007", ICD10, "J18.9", "equivalent");
            saveConceptMap(SNOMED, "86406008", ICD10, "B20", "equivalent");
        }

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
     * or HL7 FHIR R4 {@code CodeSystem} JSON payloads into {@code hc_code_systems}.
     */
    @Transactional
    @CacheEvict(cacheNames = {"terminologyConcepts", "snomedClosure"}, allEntries = true)
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
            case "SNOMED_RF2_RELATIONSHIP" -> {
                Map<String, Set<String>> childrenByParent = new HashMap<>();
                String[] lines = content.split("\\r?\\n");
                for (String rawLine : lines) {
                    String line = rawLine.trim();
                    if (line.isEmpty() || line.startsWith("id\t")) {
                        continue;
                    }
                    String[] cols = line.split("\\t", -1);
                    if (cols.length < 8 || !"1".equals(cols[2].trim()) || !"116680003".equals(cols[7].trim())) {
                        skipped++;
                        continue;
                    }
                    String child = cols[4].trim();
                    String parent = cols[5].trim();
                    if (!child.isBlank() && !parent.isBlank() && !child.equals(parent)) {
                        childrenByParent.computeIfAbsent(parent, k -> new LinkedHashSet<>()).add(child);
                    }
                }
                List<ConceptClosureEntity> toSave = computeTransitiveClosureEdges(SNOMED, childrenByParent);
                for (ConceptClosureEntity edge : toSave) {
                    if (!closureRepo.existsBySystemUriAndAncestorCodeAndDescendantCode(
                            SNOMED, edge.getAncestorCode(), edge.getDescendantCode())) {
                        closureRepo.save(edge);
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

    /**
     * Computes cycle-safe multi-hop BFS transitive closure edges {@code (ancestor -> descendant, depth)}
     * from a parent-to-children IS-A adjacency graph for O(1) cross-DB CDSS subsumption queries.
     */
    public static List<ConceptClosureEntity> computeTransitiveClosureEdges(
            String systemUri, Map<String, Set<String>> childrenByParent) {
        List<ConceptClosureEntity> edges = new ArrayList<>();
        for (String ancestor : childrenByParent.keySet()) {
            Map<String, Integer> minDepth = new LinkedHashMap<>();
            ArrayDeque<String> queue = new ArrayDeque<>();
            for (String directChild : childrenByParent.getOrDefault(ancestor, Set.of())) {
                if (!directChild.equals(ancestor) && minDepth.putIfAbsent(directChild, 1) == null) {
                    queue.add(directChild);
                }
            }
            while (!queue.isEmpty()) {
                String current = queue.poll();
                int nextDepth = minDepth.get(current) + 1;
                if (nextDepth > 32) continue;
                for (String child : childrenByParent.getOrDefault(current, Set.of())) {
                    if (!child.equals(ancestor) && minDepth.putIfAbsent(child, nextDepth) == null) {
                        queue.add(child);
                    }
                }
            }
            for (Map.Entry<String, Integer> e : minDepth.entrySet()) {
                ConceptClosureEntity closure = new ConceptClosureEntity();
                closure.setSystemUri(systemUri);
                closure.setAncestorCode(ancestor);
                closure.setDescendantCode(e.getKey());
                closure.setDepth(e.getValue());
                edges.add(closure);
            }
        }
        return edges;
    }

    private static Optional<CodeConceptEntity> findInCanonicalCatalog(String systemUri, String code) {
        for (SeedConceptSpec spec : CANONICAL_STARTER_CONCEPTS) {
            if (spec.systemUri().equals(systemUri) && spec.code().equalsIgnoreCase(code)) {
                return Optional.of(toEntity(spec));
            }
        }
        return Optional.empty();
    }

    private static CodeConceptEntity toEntity(SeedConceptSpec spec) {
        CodeConceptEntity c = new CodeConceptEntity();
        c.setSystemUri(spec.systemUri());
        c.setCode(spec.code());
        c.setDisplayEn(spec.displayEn());
        c.setDisplayFr(spec.displayFr());
        c.setClinicalDomain(spec.clinicalDomain());
        c.setUcumUnit(spec.ucumUnit());
        c.setActive(true);
        return c;
    }

    private static List<ConceptMapEntity> findBuiltInTranslations(String sourceSystem, String sourceCode) {
        Map<String, String> snomedToIcd10 = Map.of(
                "38341003", "I10",
                "44054006", "E11.9",
                "61462000", "B50.9",
                "233604007", "J18.9",
                "86406008", "B20"
        );
        if (SNOMED.equals(sourceSystem) && snomedToIcd10.containsKey(sourceCode)) {
            ConceptMapEntity cm = new ConceptMapEntity();
            cm.setSourceSystem(SNOMED);
            cm.setSourceCode(sourceCode);
            cm.setTargetSystem(ICD10);
            cm.setTargetCode(snomedToIcd10.get(sourceCode));
            cm.setEquivalence("61462000".equals(sourceCode) ? "narrower" : "equivalent");
            return List.of(cm);
        }
        for (Map.Entry<String, String> entry : snomedToIcd10.entrySet()) {
            if (ICD10.equals(sourceSystem) && entry.getValue().equalsIgnoreCase(sourceCode)) {
                ConceptMapEntity cm = new ConceptMapEntity();
                cm.setSourceSystem(ICD10);
                cm.setSourceCode(entry.getValue());
                cm.setTargetSystem(SNOMED);
                cm.setTargetCode(entry.getKey());
                cm.setEquivalence("equivalent");
                return List.of(cm);
            }
        }
        return List.of();
    }

    private boolean areUcumUnitsCompatible(String canonicalUnit, String candidateUnit) {
        if (canonicalUnit.equalsIgnoreCase(candidateUnit)) {
            return true;
        }
        try {
            convertUcum(BigDecimal.ONE, candidateUnit, canonicalUnit);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int countActiveForSystem(String systemUri, List<CodeConceptEntity> persisted) {
        Set<String> codes = new LinkedHashSet<>();
        for (SeedConceptSpec spec : CANONICAL_STARTER_CONCEPTS) {
            if (systemUri.equals(spec.systemUri())) {
                codes.add(spec.code().toUpperCase(Locale.ROOT));
            }
        }
        for (CodeConceptEntity e : persisted) {
            if (systemUri.equals(e.getSystemUri())) {
                if (e.isActive()) {
                    codes.add(e.getCode().toUpperCase(Locale.ROOT));
                } else {
                    codes.remove(e.getCode().toUpperCase(Locale.ROOT));
                }
            }
        }
        return codes.size();
    }

    private static String buildSyntaxErrorMessage(String systemUri, String systemName, String code) {
        return switch (systemUri) {
            case LOINC -> "Malformed LOINC code '" + code + "': must match NNNNN-C with a valid Regenstrief Mod-10 Luhn check digit (e.g., '8867-4', '8480-6').";
            case SNOMED -> "Malformed SNOMED-CT SCTID '" + code + "': must be 6-18 digits without leading zero and carry a valid concept partition ('00' or '10').";
            case ICD10 -> "Malformed WHO ICD-10 code '" + code + "': must start with letter A-Z (excluding U) followed by 2 digits and optional '.1-4' subcategory (e.g., 'I10', 'B50.9').";
            case ATC -> "Malformed WHO ATC code '" + code + "': must match 7-character pattern LDDLLDD (e.g., 'J01CA04').";
            case RXNORM -> "Malformed RxNorm RxCUI '" + code + "': must be a 1-9 digit numeric identifier (e.g., '723', '6809').";
            default -> "Malformed code '" + code + "' for code system " + systemName + " (" + systemUri + ").";
        };
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

    private void saveClosureEdge(String sys, String ancestor, String descendant, int depth) {
        ConceptClosureEntity edge = new ConceptClosureEntity();
        edge.setSystemUri(sys);
        edge.setAncestorCode(ancestor);
        edge.setDescendantCode(descendant);
        edge.setDepth(depth);
        closureRepo.save(edge);
    }

    private void saveConceptMap(String srcSys, String srcCode, String tgtSys, String tgtCode, String equivalence) {
        ConceptMapEntity cm = new ConceptMapEntity();
        cm.setSourceSystem(srcSys);
        cm.setSourceCode(srcCode);
        cm.setTargetSystem(tgtSys);
        cm.setTargetCode(tgtCode);
        cm.setEquivalence(equivalence);
        mapRepo.save(cm);
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
