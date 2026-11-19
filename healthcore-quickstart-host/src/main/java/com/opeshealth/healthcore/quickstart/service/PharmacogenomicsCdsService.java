/*
 * Copyright (c) 2026 Opes Health Systems (https://www.opeshealthsystems.com)
 *
 * Author: Jude Nshome — Opes Health Systems
 * Email: nshome@opesware.com
 * Organization: Opes Health Systems, Douala, Cameroon
 *
 * Licensed under the Opes HealthCore Enterprise SDK License.
 */
package com.opeshealth.healthcore.quickstart.service;

import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.service.PatientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pharmacogenomics (PGx) &amp; CPIC Precision Medicine Decision Support Service.
 *
 * <p>Manages patient pharmacogenomic allele diplotypes ({@code CYP2C19}, {@code CYP2D6},
 * {@code CYP2C9}, {@code VKORC1}, {@code TPMT}, {@code DPYD}, {@code HLA-B*57:01}, {@code G6PD},
 * {@code SLCO1B1}) and evaluates candidate drug prescriptions against CPIC (Clinical Pharmacogenetics
 * Implementation Consortium) Level A clinical practice guidelines.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class PharmacogenomicsCdsService {

    public enum MetabolizerPhenotype {
        ULTRA_RAPID_METABOLIZER("Ultra-Rapid Metabolizer (UM)"),
        NORMAL_METABOLIZER("Normal Metabolizer (NM)"),
        INTERMEDIATE_METABOLIZER("Intermediate Metabolizer (IM)"),
        POOR_METABOLIZER("Poor Metabolizer (PM)"),
        HIGH_RISK_HYPERSENSITIVITY("High-Risk Allele Positive"),
        ENZYME_DEFICIENT("Enzyme Deficient (High Hemolysis / Toxicity Risk)"),
        LOW_RISK_NEGATIVE("Low-Risk / Negative");

        private final String display;

        MetabolizerPhenotype(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }
    }

    public enum PgxAlertSeverity {
        CONTRAINDICATED_CRITICAL,
        DOSE_ADJUSTMENT_REQUIRED,
        ALTERNATIVE_THERAPY_RECOMMENDED,
        STANDARD_DOSING_SAFE
    }

    public record PgxAlleleRegistrationRequest(
            UUID facilityId,
            UUID patientId,
            String geneSymbol,
            String diplotypeOrAllele,
            MetabolizerPhenotype explicitPhenotype,
            String laboratoryReference
    ) {}

    public record PatientPgxGeneProfile(
            UUID profileId,
            UUID facilityId,
            UUID patientId,
            String geneSymbol,
            String hgncCode,
            String diplotypeOrAllele,
            MetabolizerPhenotype phenotype,
            String loincCode,
            String laboratoryReference,
            String fhirObservationJson,
            Instant recordedAt
    ) {}

    public record PgxPrescriptionCheckRequest(
            UUID facilityId,
            UUID patientId,
            String medicationName,
            String rxNormOrAtcCode,
            String proposedDose
    ) {}

    public record PgxPrescribingAlert(
            UUID evaluationId,
            UUID patientId,
            String medicationName,
            String rxNormOrAtcCode,
            String implicatedGene,
            String patientDiplotype,
            MetabolizerPhenotype patientPhenotype,
            PgxAlertSeverity severity,
            String cpicEvidenceLevel,
            String clinicalMechanism,
            String dosingRecommendation,
            List<String> recommendedAlternatives,
            boolean blockAdministrationWithoutOverride,
            Instant evaluatedAt
    ) {}

    private static final Map<String, String> GENE_TO_LOINC = Map.of(
            "CYP2C19", "57132-3",
            "CYP2D6", "79716-7",
            "CYP2C9", "46724-1",
            "VKORC1", "50722-8",
            "TPMT", "79714-2",
            "DPYD", "79715-9",
            "HLA-B*57:01", "57290-9",
            "G6PD", "32550-6",
            "SLCO1B1", "79718-3"
    );

    private static final Map<String, String> GENE_TO_HGNC = Map.of(
            "CYP2C19", "HGNC:2621",
            "CYP2D6", "HGNC:2625",
            "CYP2C9", "HGNC:2623",
            "VKORC1", "HGNC:23663",
            "TPMT", "HGNC:12014",
            "DPYD", "HGNC:3012",
            "HLA-B*57:01", "HGNC:4932",
            "G6PD", "HGNC:4057",
            "SLCO1B1", "HGNC:10959"
    );

    private final FhirAuditLoggingService fhirAuditLoggingService;
    private final Map<UUID, Map<String, PatientPgxGeneProfile>> profilesByPatient = new ConcurrentHashMap<>();

    @Autowired
    public PharmacogenomicsCdsService(
            @Autowired(required = false) FhirAuditLoggingService fhirAuditLoggingService) {
        this.fhirAuditLoggingService = fhirAuditLoggingService;
    }

    public PharmacogenomicsCdsService() {
        this(null);
    }

    /**
     * Registers a pharmacogenomic diplotype/allele finding for a patient and derives the CPIC phenotype.
     */
    @ClinicalAudit(action = "PGX_GENOTYPE_REGISTER", resourceType = "Observation")
    public PatientPgxGeneProfile registerPatientGenotype(PgxAlleleRegistrationRequest request) {
        if (request == null || request.patientId() == null) {
            throw new IllegalArgumentException("PgxAlleleRegistrationRequest with patientId is required");
        }
        if (request.geneSymbol() == null || request.geneSymbol().isBlank()) {
            throw new IllegalArgumentException("PGx geneSymbol is required (e.g., CYP2C19, CYP2D6, HLA-B*57:01, G6PD)");
        }
        if (request.diplotypeOrAllele() == null || request.diplotypeOrAllele().isBlank()) {
            throw new IllegalArgumentException("PGx diplotypeOrAllele is required (e.g., *2/*2, *1/*17, POSITIVE, DEFICIENT)");
        }

        String normalizedGene = request.geneSymbol().trim().toUpperCase(Locale.ROOT);
        String diplotype = request.diplotypeOrAllele().trim();
        MetabolizerPhenotype phenotype = request.explicitPhenotype() != null
                ? request.explicitPhenotype()
                : inferPhenotype(normalizedGene, diplotype);

        UUID profileId = UUID.randomUUID();
        UUID facilityId = request.facilityId() != null ? request.facilityId() : PatientService.DEFAULT_FACILITY_ID;
        String loinc = GENE_TO_LOINC.getOrDefault(normalizedGene, "69548-6");
        String hgnc = GENE_TO_HGNC.getOrDefault(normalizedGene, "HGNC:0000");

        String fhirJson = """
                {
                  "resourceType": "Observation",
                  "id": "%s",
                  "status": "final",
                  "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/observation-category", "code": "laboratory"}]}],
                  "code": {"coding": [{"system": "http://loinc.org", "code": "%s", "display": "%s Pharmacogenomic Genotype"}]},
                  "subject": {"reference": "Patient/%s"},
                  "valueCodeableConcept": {
                    "text": "%s %s (%s)"
                  }
                }
                """.formatted(profileId, loinc, normalizedGene, request.patientId(),
                normalizedGene, diplotype, phenotype.display()).trim();

        PatientPgxGeneProfile profile = new PatientPgxGeneProfile(
                profileId,
                facilityId,
                request.patientId(),
                normalizedGene,
                hgnc,
                diplotype,
                phenotype,
                loinc,
                request.laboratoryReference() != null ? request.laboratoryReference().trim() : "OPES-PGX-PANEL",
                fhirJson,
                Instant.now()
        );

        profilesByPatient
                .computeIfAbsent(request.patientId(), k -> new ConcurrentHashMap<>())
                .put(normalizedGene, profile);

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAuditLoggingService.FhirAccessAuditCommand(
                    facilityId.toString(),
                    "PGX_GENOMICS_LAB",
                    "GENETICIST",
                    "PGX_GENOTYPE_REGISTERED",
                    "Observation",
                    profileId.toString(),
                    request.patientId(),
                    "POST",
                    "/api/v1/pgx/genotypes",
                    "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "Registered PGx genotype " + normalizedGene + " " + diplotype + " -> " + phenotype
            ));
        }

        return profile;
    }

    public List<PatientPgxGeneProfile> getPatientGenotypes(UUID patientId) {
        Objects.requireNonNull(patientId, "patientId is required");
        Map<String, PatientPgxGeneProfile> map = profilesByPatient.get(patientId);
        if (map == null) {
            return List.of();
        }
        return map.values().stream()
                .sorted(Comparator.comparing(PatientPgxGeneProfile::geneSymbol))
                .toList();
    }

    /**
     * Evaluates a medication order against the patient's pharmacogenomic profile using CPIC Level A rules.
     */
    @ClinicalAudit(action = "PGX_PRESCRIPTION_EVALUATE", resourceType = "MedicationRequest")
    public PgxPrescribingAlert evaluateMedicationOrder(PgxPrescriptionCheckRequest request) {
        if (request == null || request.patientId() == null) {
            throw new IllegalArgumentException("PgxPrescriptionCheckRequest with patientId is required");
        }
        if (request.medicationName() == null || request.medicationName().isBlank()) {
            throw new IllegalArgumentException("medicationName is required for PGx decision support");
        }

        String medLower = request.medicationName().trim().toLowerCase(Locale.ROOT);
        Map<String, PatientPgxGeneProfile> genes = profilesByPatient.getOrDefault(request.patientId(), Map.of());

        // 1. Clopidogrel <-> CYP2C19
        if (medLower.contains("clopidogrel") || "B01AC04".equalsIgnoreCase(request.rxNormOrAtcCode())) {
            PatientPgxGeneProfile cyp2c19 = genes.get("CYP2C19");
            if (cyp2c19 != null && (cyp2c19.phenotype() == MetabolizerPhenotype.POOR_METABOLIZER
                    || cyp2c19.phenotype() == MetabolizerPhenotype.INTERMEDIATE_METABOLIZER)) {
                boolean isPoor = cyp2c19.phenotype() == MetabolizerPhenotype.POOR_METABOLIZER;
                return new PgxPrescribingAlert(
                        UUID.randomUUID(),
                        request.patientId(),
                        request.medicationName(),
                        request.rxNormOrAtcCode(),
                        "CYP2C19",
                        cyp2c19.diplotypeOrAllele(),
                        cyp2c19.phenotype(),
                        isPoor ? PgxAlertSeverity.CONTRAINDICATED_CRITICAL : PgxAlertSeverity.ALTERNATIVE_THERAPY_RECOMMENDED,
                        "CPIC Level A",
                        "Reduced CYP2C19 bioactivation of clopidogrel prodrug leads to diminished platelet inhibition and high risk of stent thrombosis / major adverse cardiovascular events.",
                        "Avoid clopidogrel. Switch to an alternative P2Y12 inhibitor not dependent on CYP2C19 activation.",
                        List.of("Prasugrel 10 mg daily", "Ticagrelor 90 mg twice daily"),
                        isPoor,
                        Instant.now()
                );
            }
        }

        // 2. Abacavir <-> HLA-B*57:01
        if (medLower.contains("abacavir") || "J05AF06".equalsIgnoreCase(request.rxNormOrAtcCode())) {
            PatientPgxGeneProfile hlab = genes.get("HLA-B*57:01");
            if (hlab != null && hlab.phenotype() == MetabolizerPhenotype.HIGH_RISK_HYPERSENSITIVITY) {
                return new PgxPrescribingAlert(
                        UUID.randomUUID(),
                        request.patientId(),
                        request.medicationName(),
                        request.rxNormOrAtcCode(),
                        "HLA-B*57:01",
                        hlab.diplotypeOrAllele(),
                        hlab.phenotype(),
                        PgxAlertSeverity.CONTRAINDICATED_CRITICAL,
                        "CPIC Level A / WHO HIV Guideline",
                        "HLA-B*57:01 positive status carries a >50% risk of potentially fatal Abacavir Hypersensitivity Reaction (HSR).",
                        "CONTRAINDICATED: Do not initiate abacavir. Select an alternative NRTI backbone.",
                        List.of("Tenofovir Disoproxil Fumarate (TDF)", "Tenofovir Alafenamide (TAF)", "Zidovudine (AZT)"),
                        true,
                        Instant.now()
                );
            }
        }

        // 3. Primaquine / Tafenoquine / Rasburicase / Dapsone <-> G6PD Deficiency
        if (medLower.contains("primaquine") || medLower.contains("tafenoquine")
                || medLower.contains("dapsone") || medLower.contains("rasburicase")) {
            PatientPgxGeneProfile g6pd = genes.get("G6PD");
            if (g6pd != null && g6pd.phenotype() == MetabolizerPhenotype.ENZYME_DEFICIENT) {
                return new PgxPrescribingAlert(
                        UUID.randomUUID(),
                        request.patientId(),
                        request.medicationName(),
                        request.rxNormOrAtcCode(),
                        "G6PD",
                        g6pd.diplotypeOrAllele(),
                        g6pd.phenotype(),
                        PgxAlertSeverity.CONTRAINDICATED_CRITICAL,
                        "CPIC Level A / WHO Malaria Radical Cure Guideline",
                        "G6PD deficiency causes severe acute oxidative hemolytic anemia when exposed to 8-aminoquinolines or sulfones.",
                        "CONTRAINDICATED in severe G6PD deficiency; for mild/moderate WHO Class B deficiency consider supervised weekly primaquine 0.75 mg/kg/week for 8 weeks only if clinically indicated.",
                        List.of("Supervised weekly adjusted antimalarial protocol", "Artemisinin-based combination therapy (ACT) without 8-aminoquinoline"),
                        true,
                        Instant.now()
                );
            }
        }

        // 4. Codeine / Tramadol <-> CYP2D6
        if (medLower.contains("codeine") || medLower.contains("tramadol")) {
            PatientPgxGeneProfile cyp2d6 = genes.get("CYP2D6");
            if (cyp2d6 != null && (cyp2d6.phenotype() == MetabolizerPhenotype.ULTRA_RAPID_METABOLIZER
                    || cyp2d6.phenotype() == MetabolizerPhenotype.POOR_METABOLIZER)) {
                boolean ultra = cyp2d6.phenotype() == MetabolizerPhenotype.ULTRA_RAPID_METABOLIZER;
                return new PgxPrescribingAlert(
                        UUID.randomUUID(),
                        request.patientId(),
                        request.medicationName(),
                        request.rxNormOrAtcCode(),
                        "CYP2D6",
                        cyp2d6.diplotypeOrAllele(),
                        cyp2d6.phenotype(),
                        ultra ? PgxAlertSeverity.CONTRAINDICATED_CRITICAL : PgxAlertSeverity.ALTERNATIVE_THERAPY_RECOMMENDED,
                        "CPIC Level A",
                        ultra
                                ? "CYP2D6 Ultra-Rapid conversion of codeine to morphine causes life-threatening respiratory depression."
                                : "CYP2D6 Poor Metabolizer cannot convert prodrug to active analgesic metabolite, resulting in therapeutic failure.",
                        "Avoid codeine and tramadol. Use a non-CYP2D6 dependent analgesic.",
                        List.of("Morphine (direct active opioid)", "Hydromorphone", "Acetaminophen / NSAIDs"),
                        ultra,
                        Instant.now()
                );
            }
        }

        // 5. Azathioprine / Mercaptopurine <-> TPMT
        if (medLower.contains("azathioprine") || medLower.contains("mercaptopurine")) {
            PatientPgxGeneProfile tpmt = genes.get("TPMT");
            if (tpmt != null && (tpmt.phenotype() == MetabolizerPhenotype.POOR_METABOLIZER
                    || tpmt.phenotype() == MetabolizerPhenotype.INTERMEDIATE_METABOLIZER)) {
                boolean isPoor = tpmt.phenotype() == MetabolizerPhenotype.POOR_METABOLIZER;
                return new PgxPrescribingAlert(
                        UUID.randomUUID(),
                        request.patientId(),
                        request.medicationName(),
                        request.rxNormOrAtcCode(),
                        "TPMT",
                        tpmt.diplotypeOrAllele(),
                        tpmt.phenotype(),
                        isPoor ? PgxAlertSeverity.CONTRAINDICATED_CRITICAL : PgxAlertSeverity.DOSE_ADJUSTMENT_REQUIRED,
                        "CPIC Level A",
                        "Deficient TPMT methylation causes accumulation of cytotoxic thioguanine nucleotides and severe life-threatening myelosuppression.",
                        isPoor
                                ? "Reduce dose 10-fold and administer thrice weekly, or select a non-thiopurine immunosuppressant."
                                : "Start at 30–80% of standard target dose and monitor CBC closely.",
                        List.of("Mycophenolate Mofetil", "Reduced-dose Thiopurine Protocol"),
                        isPoor,
                        Instant.now()
                );
            }
        }

        // 6. Simvastatin <-> SLCO1B1
        if (medLower.contains("simvastatin")) {
            PatientPgxGeneProfile slco1b1 = genes.get("SLCO1B1");
            if (slco1b1 != null && (slco1b1.phenotype() == MetabolizerPhenotype.POOR_METABOLIZER
                    || slco1b1.phenotype() == MetabolizerPhenotype.INTERMEDIATE_METABOLIZER)) {
                return new PgxPrescribingAlert(
                        UUID.randomUUID(),
                        request.patientId(),
                        request.medicationName(),
                        request.rxNormOrAtcCode(),
                        "SLCO1B1",
                        slco1b1.diplotypeOrAllele(),
                        slco1b1.phenotype(),
                        PgxAlertSeverity.DOSE_ADJUSTMENT_REQUIRED,
                        "CPIC Level A",
                        "Decreased hepatic OATP1B1 transporter uptake increases systemic simvastatin acid exposure and statin-associated myopathy / rhabdomyolysis risk.",
                        "Prescribe <= 20 mg/day simvastatin or switch to rosuvastatin or pravastatin.",
                        List.of("Rosuvastatin", "Pravastatin", "Atorvastatin"),
                        false,
                        Instant.now()
                );
            }
        }

        return new PgxPrescribingAlert(
                UUID.randomUUID(),
                request.patientId(),
                request.medicationName(),
                request.rxNormOrAtcCode(),
                "NONE_ACTIONABLE",
                "WILD_TYPE_OR_NOT_TESTED",
                MetabolizerPhenotype.NORMAL_METABOLIZER,
                PgxAlertSeverity.STANDARD_DOSING_SAFE,
                "CPIC Level A",
                "No actionable pharmacogenomic contraindications detected for this patient and medication.",
                "Proceed with standard clinical dosing guidelines.",
                List.of(),
                false,
                Instant.now()
        );
    }

    private static MetabolizerPhenotype inferPhenotype(String gene, String diplotype) {
        String upper = diplotype.toUpperCase(Locale.ROOT);
        if ("HLA-B*57:01".equals(gene)) {
            return (upper.contains("POS") || upper.contains("CARRIER") || upper.contains("*57:01"))
                    ? MetabolizerPhenotype.HIGH_RISK_HYPERSENSITIVITY
                    : MetabolizerPhenotype.LOW_RISK_NEGATIVE;
        }
        if ("G6PD".equals(gene)) {
            return (upper.contains("DEF") || upper.contains("A-") || upper.contains("MEDITERRANEAN") || upper.contains("LOW"))
                    ? MetabolizerPhenotype.ENZYME_DEFICIENT
                    : MetabolizerPhenotype.NORMAL_METABOLIZER;
        }
        if (upper.contains("*17/*17") || upper.contains("XN") || upper.contains("UM") || upper.contains("ULTRA")) {
            return MetabolizerPhenotype.ULTRA_RAPID_METABOLIZER;
        }
        if (upper.equals("*2/*2") || upper.equals("*2/*3") || upper.equals("*3/*3")
                || upper.equals("*4/*4") || upper.equals("*5/*5") || upper.contains("PM") || upper.contains("POOR")) {
            return MetabolizerPhenotype.POOR_METABOLIZER;
        }
        if (upper.contains("*1/*2") || upper.contains("*1/*3") || upper.contains("*1/*4")
                || upper.contains("*1/*5") || upper.contains("IM") || upper.contains("INTERMEDIATE")) {
            return MetabolizerPhenotype.INTERMEDIATE_METABOLIZER;
        }
        return MetabolizerPhenotype.NORMAL_METABOLIZER;
    }
}
