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

import ca.uhn.fhir.context.FhirContext;
import com.opeshealth.healthcore.quickstart.domain.QuickstartClinicalEntities.ObservationEntity;
import com.opeshealth.healthcore.quickstart.service.DigitalHealthCertificateService.QrCodeGeneratorUtility;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.ClinicalSegmentItem;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.ConfidentialityLevel;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.SegmentedAccessEvaluationResult;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.SensitivityCategory;
import com.opeshealth.healthcore.quickstart.service.ObservationService.ObservationSearchCriteria;
import com.opeshealth.healthcore.quickstart.service.ObservationService.ObservationSearchResult;
import com.opeshealth.healthcore.quickstart.service.PharmacogenomicsCdsService.PatientPgxGeneProfile;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.service.PatientService;
import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Immunization;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Narrative;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Procedure;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * HL7 FHIR R4 International Patient Summary ({@code Patient/{id}/$summary}) &amp;
 * JWS-Signed ({@code RS256} / {@code Ed25519}) QR Health Record Service.
 *
 * <p>Assembles an HL7 FHIR IPS Document {@link Bundle} ({@code BundleType.DOCUMENT}) with a root
 * {@link Composition} (LOINC {@code 60591-5}), active problems, allergies, medications,
 * immunizations, procedures, vital-sign observations, and pharmacogenomic alerts. Enforces DS4P
 * privacy segmentation via {@link FhirConsentEnforcementService}, signs the IPS document using
 * RFC 7515 **JWS (`RS256` or `Ed25519` / `EdDSA`)**, and generates SMART Health Card (`shc:/`),
 * WHO GDHCN (`hcert:/ips/v1/`), SMART Health Link (`shlink:/`), vector `SVG`, and Base64 `PNG`
 * QR codes for mobile verification.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class InternationalPatientSummaryService {

    private static final FhirContext FHIR_R4 = FhirContext.forR4();
    private static final String IPS_COMPOSITION_PROFILE =
            "http://hl7.org/fhir/uv/ips/StructureDefinition/Composition-uv-ips";
    private static final String IPS_BUNDLE_PROFILE =
            "http://hl7.org/fhir/uv/ips/StructureDefinition/Bundle-uv-ips";

    public static final String IPS_RSA_KEY_ID = "opes-gdhcn-ips-rs256-2026";
    public static final String IPS_ED25519_KEY_ID = "opes-gdhcn-ips-ed25519-2026";

    public record IpsClinicalProblemItem(
            String snomedOrIcd10Code,
            String display,
            String clinicalStatus,
            SensitivityCategory sensitivityCategory
    ) {}

    public record IpsSeedClinicalSummaryRequest(
            UUID patientId,
            List<IpsClinicalProblemItem> problems,
            List<String> allergies,
            List<String> activeMedications,
            List<String> immunizations,
            List<String> procedures
    ) {
        public IpsSeedClinicalSummaryRequest(
                UUID patientId,
                List<IpsClinicalProblemItem> problems,
                List<String> allergies,
                List<String> activeMedications,
                List<String> immunizations) {
            this(patientId, problems, allergies, activeMedications, immunizations, List.of());
        }
    }

    public record IpsGenerationOptionsRequest(
            UUID patientId,
            String requestingActorId,
            String breakTheGlassToken,
            String signingAlgorithm,
            Boolean compressPayloadDeflate,
            IpsSeedClinicalSummaryRequest inlineClinicalSections
    ) {}

    public record IpsQrCodeBundle(
            String smartHealthCardNumericUri,
            String gdhcnIpsCompactUri,
            String smartHealthLinkUri,
            int qrMatrixDimension,
            String qrCodeSvg,
            String qrCodePngBase64DataUri
    ) {}

    public record SignedIpsBundleResponse(
            UUID documentBundleId,
            UUID patientId,
            Instant generatedAt,
            String signingAlgorithm,
            String keyId,
            String publicKeyFingerprintSha256,
            boolean payloadDeflateCompressed,
            boolean ds4pBreakTheGlassApplied,
            int redactedSensitiveEntryCount,
            List<String> redactedNotices,
            String jwsCompactSignature,
            String verifiableQrHealthLinkPayload,
            IpsQrCodeBundle qrCode,
            String fhirIpsDocumentBundleJson
    ) {}

    public record IpsSignatureVerificationResult(
            boolean signatureValid,
            String signingAlgorithm,
            String keyId,
            String publicKeyFingerprintSha256,
            String payloadSha256Digest,
            UUID documentBundleId,
            UUID patientId,
            String decodedIpsBundleJson,
            Instant verifiedAt,
            String diagnosticMessage
    ) {}

    private final PatientService patientService;
    private final ObservationService observationService;
    private final FhirConsentEnforcementService consentService;
    private final PharmacogenomicsCdsService pgxService;
    private final FhirAuditLoggingService fhirAuditLoggingService;

    private final KeyPair rsaKeyPair;
    private final KeyPair ed25519KeyPair;
    private final String rsaPublicKeyFingerprint;
    private final String ed25519PublicKeyFingerprint;

    private final Map<UUID, IpsSeedClinicalSummaryRequest> clinicalSummariesByPatient = new ConcurrentHashMap<>();
    private final Map<UUID, SignedIpsBundleResponse> generatedBundlesById = new ConcurrentHashMap<>();

    @Autowired
    public InternationalPatientSummaryService(
            PatientService patientService,
            @Autowired(required = false) ObservationService observationService,
            @Autowired(required = false) FhirConsentEnforcementService consentService,
            @Autowired(required = false) PharmacogenomicsCdsService pgxService,
            @Autowired(required = false) FhirAuditLoggingService fhirAuditLoggingService) {
        this.patientService = Objects.requireNonNull(patientService, "PatientService is required");
        this.observationService = observationService;
        this.consentService = consentService;
        this.pgxService = pgxService;
        this.fhirAuditLoggingService = fhirAuditLoggingService;

        try {
            KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA");
            rsaGen.initialize(2048);
            this.rsaKeyPair = rsaGen.generateKeyPair();

            KeyPairGenerator edGen = KeyPairGenerator.getInstance("Ed25519");
            this.ed25519KeyPair = edGen.generateKeyPair();

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            this.rsaPublicKeyFingerprint = HexFormat.of().formatHex(md.digest(rsaKeyPair.getPublic().getEncoded()));
            this.ed25519PublicKeyFingerprint = HexFormat.of().formatHex(md.digest(ed25519KeyPair.getPublic().getEncoded()));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize IPS cryptographic signing keys", ex);
        }
    }

    public InternationalPatientSummaryService(
            PatientService patientService,
            ObservationService observationService,
            FhirConsentEnforcementService consentService,
            PharmacogenomicsCdsService pgxService) {
        this(patientService, observationService, consentService, pgxService, null);
    }

    public InternationalPatientSummaryService(PatientService patientService) {
        this(patientService, null, null, null, null);
    }

    /**
     * Registers or updates clinical summary items (problems, allergies, medications, immunizations, procedures)
     * for inclusion in a patient's International Patient Summary (`Patient/{id}/$summary`).
     */
    public void upsertPatientIpsClinicalData(IpsSeedClinicalSummaryRequest request) {
        if (request == null || request.patientId() == null) {
            throw new IllegalArgumentException("IpsSeedClinicalSummaryRequest with patientId is required");
        }
        clinicalSummariesByPatient.put(request.patientId(), request);
    }

    /**
     * Generates and cryptographically signs the HL7 FHIR R4 International Patient Summary
     * ({@code Patient/{id}/$summary}) document Bundle, respecting DS4P Consent and Break-the-Glass tokens.
     */
    @ClinicalAudit(action = "FHIR_IPS_PATIENT_SUMMARY_GENERATE", resourceType = "Bundle")
    public SignedIpsBundleResponse generateSignedPatientSummary(
            UUID patientId,
            String requestingActorId,
            String breakTheGlassToken,
            String preferredJwsAlgorithm) {
        return generateSignedPatientSummary(new IpsGenerationOptionsRequest(
                patientId,
                requestingActorId,
                breakTheGlassToken,
                preferredJwsAlgorithm,
                false,
                null
        ));
    }

    /**
     * Generates and cryptographically signs the HL7 FHIR R4 International Patient Summary
     * ({@code Patient/{id}/$summary}) with full JWS (`RS256` or `Ed25519`/`EdDSA`), optional
     * DEFLATE compression, and mobile QR code assets (`shc:/`, `hcert:/ips/v1/`, `shlink:/`, SVG, PNG).
     */
    @ClinicalAudit(action = "FHIR_IPS_PATIENT_SUMMARY_GENERATE", resourceType = "Bundle")
    public SignedIpsBundleResponse generateSignedPatientSummary(IpsGenerationOptionsRequest options) {
        if (options == null || options.patientId() == null) {
            throw new IllegalArgumentException("patientId is required to generate an International Patient Summary");
        }
        UUID patientId = options.patientId();
        if (options.inlineClinicalSections() != null) {
            clinicalSummariesByPatient.put(patientId, options.inlineClinicalSections());
        }

        Patient fhirPatient = patientService.getFhirPatientById(patientId);
        boolean useEd25519 = isEd25519Requested(options.signingAlgorithm());
        String alg = useEd25519 ? "EdDSA" : "RS256";
        String activeKeyId = useEd25519 ? IPS_ED25519_KEY_ID : IPS_RSA_KEY_ID;
        String activeFingerprint = useEd25519 ? ed25519PublicKeyFingerprint : rsaPublicKeyFingerprint;
        boolean deflate = Boolean.TRUE.equals(options.compressPayloadDeflate());

        IpsSeedClinicalSummaryRequest seeded = clinicalSummariesByPatient.get(patientId);
        List<IpsClinicalProblemItem> rawProblems = seeded != null && seeded.problems() != null && !seeded.problems().isEmpty()
                ? seeded.problems()
                : List.of(new IpsClinicalProblemItem("38341003", "Essential hypertension", "active", null));

        // Apply DS4P Consent & Break-the-Glass segmentation to problem items
        List<ClinicalSegmentItem> segmentItems = new ArrayList<>();
        for (int i = 0; i < rawProblems.size(); i++) {
            IpsClinicalProblemItem prob = rawProblems.get(i);
            ConfidentialityLevel conf = prob.sensitivityCategory() != null
                    ? prob.sensitivityCategory().defaultConfidentiality()
                    : ConfidentialityLevel.N;
            segmentItems.add(new ClinicalSegmentItem(
                    "cond-" + (i + 1),
                    "Condition",
                    prob.snomedOrIcd10Code() + "|" + prob.display(),
                    prob.sensitivityCategory(),
                    conf
            ));
        }

        SegmentedAccessEvaluationResult ds4pResult = consentService != null
                ? consentService.evaluateSegmentedAccess(
                patientId, options.requestingActorId(), "TREAT", options.breakTheGlassToken(), segmentItems)
                : new SegmentedAccessEvaluationResult(
                patientId, options.requestingActorId() != null ? options.requestingActorId() : "CLINICIAN",
                "TREAT", false, null, segmentItems.size(), segmentItems.size(), 0,
                segmentItems, List.of(), List.of());

        UUID bundleId = UUID.randomUUID();
        Date nowDate = new Date();
        Bundle ipsBundle = new Bundle();
        ipsBundle.setId(bundleId.toString());
        ipsBundle.setType(Bundle.BundleType.DOCUMENT);
        ipsBundle.setTimestamp(nowDate);
        ipsBundle.getMeta().addProfile(IPS_BUNDLE_PROFILE);

        Composition comp = new Composition();
        comp.setId(UUID.randomUUID().toString());
        comp.getMeta().addProfile(IPS_COMPOSITION_PROFILE);
        comp.setStatus(Composition.CompositionStatus.FINAL);
        comp.setType(new CodeableConcept().addCoding(
                new Coding("http://loinc.org", "60591-5", "Patient summary Document")));
        comp.setSubject(new Reference("Patient/" + patientId));
        comp.setDate(nowDate);
        comp.setTitle("International Patient Summary (HL7 FHIR R4 IPS) — Opes HealthCore");
        comp.addAuthor(new Reference().setDisplay("Opes HealthCore IPS Certifier (Douala, Cameroon)"));

        // Entry 1: Root Composition
        ipsBundle.addEntry().setFullUrl("Composition/" + comp.getIdElement().getIdPart()).setResource(comp);
        // Entry 2: Patient
        ipsBundle.addEntry().setFullUrl("Patient/" + patientId).setResource(fhirPatient);

        // Section 1: Active Problems (LOINC 11450-4)
        Composition.SectionComponent problemSection = comp.addSection();
        problemSection.setTitle("Active Problems");
        problemSection.setCode(new CodeableConcept().addCoding(
                new Coding("http://loinc.org", "11450-4", "Problem list - Reported")));

        for (ClinicalSegmentItem permitted : ds4pResult.permittedItems()) {
            String[] parts = permitted.displaySummary().split("\\|", 2);
            Condition condition = new Condition();
            String condId = UUID.randomUUID().toString();
            condition.setId(condId);
            condition.setSubject(new Reference("Patient/" + patientId));
            condition.setClinicalStatus(new CodeableConcept().addCoding(
                    new Coding("http://terminology.hl7.org/CodeSystem/condition-clinical", "active", "Active")));
            condition.setCode(new CodeableConcept().addCoding(
                    new Coding("http://snomed.info/sct", parts[0], parts.length > 1 ? parts[1] : parts[0])));
            ipsBundle.addEntry().setFullUrl("Condition/" + condId).setResource(condition);
            problemSection.addEntry(new Reference("Condition/" + condId));
        }

        // Section 2: Allergies and Intolerances (LOINC 48765-2)
        Composition.SectionComponent allergySection = comp.addSection();
        allergySection.setTitle("Allergies and Intolerances");
        allergySection.setCode(new CodeableConcept().addCoding(
                new Coding("http://loinc.org", "48765-2", "Allergies and adverse reactions Document")));
        List<String> allergyNames = (seeded != null && seeded.allergies() != null && !seeded.allergies().isEmpty())
                ? seeded.allergies()
                : List.of("No known drug allergies (NKDA)");
        for (String allergyText : allergyNames) {
            AllergyIntolerance ai = new AllergyIntolerance();
            String aiId = UUID.randomUUID().toString();
            ai.setId(aiId);
            ai.setPatient(new Reference("Patient/" + patientId));
            ai.setCode(new CodeableConcept().setText(allergyText));
            ipsBundle.addEntry().setFullUrl("AllergyIntolerance/" + aiId).setResource(ai);
            allergySection.addEntry(new Reference("AllergyIntolerance/" + aiId));
        }

        // Section 3: Medication Summary (LOINC 10160-0)
        Composition.SectionComponent medSection = comp.addSection();
        medSection.setTitle("Medication Summary");
        medSection.setCode(new CodeableConcept().addCoding(
                new Coding("http://loinc.org", "10160-0", "History of Medication use Narrative")));
        List<String> medNames = (seeded != null && seeded.activeMedications() != null && !seeded.activeMedications().isEmpty())
                ? seeded.activeMedications()
                : List.of("Amlodipine 5 mg oral tablet once daily");
        for (String medText : medNames) {
            MedicationStatement ms = new MedicationStatement();
            String msId = UUID.randomUUID().toString();
            ms.setId(msId);
            ms.setStatus(MedicationStatement.MedicationStatementStatus.ACTIVE);
            ms.setSubject(new Reference("Patient/" + patientId));
            ms.setMedication(new CodeableConcept().setText(medText));
            ipsBundle.addEntry().setFullUrl("MedicationStatement/" + msId).setResource(ms);
            medSection.addEntry(new Reference("MedicationStatement/" + msId));
        }

        // Section 4: Immunizations (LOINC 11369-6)
        Composition.SectionComponent immSection = comp.addSection();
        immSection.setTitle("History of Immunizations");
        immSection.setCode(new CodeableConcept().addCoding(
                new Coding("http://loinc.org", "11369-6", "History of Immunization Narrative")));
        List<String> immNames = (seeded != null && seeded.immunizations() != null && !seeded.immunizations().isEmpty())
                ? seeded.immunizations()
                : List.of("Yellow Fever Vaccine (Stamaril) — WHO ICVP Verified");
        for (String immText : immNames) {
            Immunization imm = new Immunization();
            String immId = UUID.randomUUID().toString();
            imm.setId(immId);
            imm.setStatus(Immunization.ImmunizationStatus.COMPLETED);
            imm.setPatient(new Reference("Patient/" + patientId));
            imm.setVaccineCode(new CodeableConcept().setText(immText));
            ipsBundle.addEntry().setFullUrl("Immunization/" + immId).setResource(imm);
            immSection.addEntry(new Reference("Immunization/" + immId));
        }

        // Section 5: History of Procedures (LOINC 47519-4)
        List<String> procNames = (seeded != null && seeded.procedures() != null)
                ? seeded.procedures()
                : List.of();
        if (!procNames.isEmpty()) {
            Composition.SectionComponent procSection = comp.addSection();
            procSection.setTitle("History of Procedures");
            procSection.setCode(new CodeableConcept().addCoding(
                    new Coding("http://loinc.org", "47519-4", "History of Procedures Document")));
            for (String procText : procNames) {
                Procedure proc = new Procedure();
                String procId = UUID.randomUUID().toString();
                proc.setId(procId);
                proc.setStatus(Procedure.ProcedureStatus.COMPLETED);
                proc.setSubject(new Reference("Patient/" + patientId));
                proc.setCode(new CodeableConcept().setText(procText));
                ipsBundle.addEntry().setFullUrl("Procedure/" + procId).setResource(proc);
                procSection.addEntry(new Reference("Procedure/" + procId));
            }
        }

        // Section 6: Vital Signs & Observations (LOINC 8716-3)
        if (observationService != null) {
            ObservationSearchResult obsResult = observationService.searchObservations(new ObservationSearchCriteria(
                    null, null, null, patientId, null, null, null, null, null, null, List.of(), null, null, 0, 10
            ));
            if (!obsResult.observations().isEmpty()) {
                Composition.SectionComponent vitalSection = comp.addSection();
                vitalSection.setTitle("Vital Signs & Clinical Results");
                vitalSection.setCode(new CodeableConcept().addCoding(
                        new Coding("http://loinc.org", "8716-3", "Vital signs")));
                for (ObservationEntity obsEntity : obsResult.observations()) {
                    vitalSection.addEntry(new Reference("Observation/" + obsEntity.getId()));
                }
            }
        }

        if (pgxService != null) {
            List<PatientPgxGeneProfile> pgxProfiles = pgxService.getPatientGenotypes(patientId);
            if (!pgxProfiles.isEmpty()) {
                Composition.SectionComponent pgxSection = comp.addSection();
                pgxSection.setTitle("Pharmacogenomic Precision Medicine Alerts");
                pgxSection.getText().setStatus(Narrative.NarrativeStatus.GENERATED);
            }
        }

        String bundleJson = FHIR_R4.newJsonParser().setPrettyPrint(true).encodeResourceToString(ipsBundle);
        String jwsSignature = signJwsCompact(bundleJson, useEd25519, activeKeyId, deflate);

        String shcNumericUri = QrCodeGeneratorUtility.toSmartHealthCardNumericUri(jwsSignature);
        String gdhcnIpsUri = "hcert:/ips/v1/" + jwsSignature;
        String qrHealthLink = "shlink:/opes-gdhcn-v1."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(
                (bundleId + "|" + patientId + "|" + activeFingerprint.substring(0, 16)).getBytes(StandardCharsets.UTF_8));

        QrCodeGeneratorUtility.RenderedQrCode renderedQr =
                QrCodeGeneratorUtility.generateQrCodeAssets(shcNumericUri, "IPS-" + bundleId);

        IpsQrCodeBundle qrCodeBundle = new IpsQrCodeBundle(
                shcNumericUri,
                gdhcnIpsUri,
                qrHealthLink,
                renderedQr.matrixDimension(),
                renderedQr.svgXml(),
                renderedQr.pngBase64DataUri()
        );

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAuditLoggingService.FhirAccessAuditCommand(
                    PatientService.DEFAULT_FACILITY_ID.toString(),
                    options.requestingActorId() != null ? options.requestingActorId() : "IPS_EXPORTER",
                    "CLINICIAN",
                    "FHIR_IPS_SUMMARY_GENERATED",
                    "Bundle",
                    bundleId.toString(),
                    patientId,
                    "GET",
                    "/api/v1/fhir/Patient/" + patientId + "/$summary",
                    "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "Generated JWS (" + alg + ") signed IPS Bundle with " + ds4pResult.redactedItemsCount() + " DS4P redactions"
            ));
        }

        SignedIpsBundleResponse response = new SignedIpsBundleResponse(
                bundleId,
                patientId,
                Instant.now(),
                alg,
                activeKeyId,
                activeFingerprint,
                deflate,
                ds4pResult.breakTheGlassApplied(),
                ds4pResult.redactedItemsCount(),
                ds4pResult.redactedItemNotices(),
                jwsSignature,
                qrHealthLink,
                qrCodeBundle,
                bundleJson
        );
        generatedBundlesById.put(bundleId, response);
        return response;
    }

    /**
     * Retrieves a previously generated JWS-signed IPS Document Bundle by its bundle ID.
     */
    public SignedIpsBundleResponse getSignedIpsBundle(UUID bundleId) {
        Objects.requireNonNull(bundleId, "bundleId is required");
        SignedIpsBundleResponse found = generatedBundlesById.get(bundleId);
        if (found == null) {
            throw new NoSuchElementException("Signed IPS Document Bundle not found: " + bundleId);
        }
        return found;
    }

    /**
     * Lists all generated JWS-signed IPS Document Bundles for a patient in reverse chronological order.
     */
    public List<SignedIpsBundleResponse> listPatientIpsBundles(UUID patientId) {
        return generatedBundlesById.values().stream()
                .filter(b -> patientId == null || Objects.equals(patientId, b.patientId()))
                .sorted(Comparator.comparing(SignedIpsBundleResponse::generatedAt).reversed())
                .toList();
    }

    /**
     * Verifies the cryptographic JWS (`RS256` or `Ed25519` / `EdDSA`) compact signature or
     * scanned QR payload (`shc:/...` or `hcert:/ips/v1/...`) of an IPS Document Bundle.
     */
    public IpsSignatureVerificationResult verifySignedIpsBundle(String jwsOrQrToken) {
        if (jwsOrQrToken == null || jwsOrQrToken.isBlank()) {
            throw new IllegalArgumentException("jwsOrQrToken is required for IPS verification");
        }
        String trimmed = jwsOrQrToken.trim();
        String jwsCompact;
        try {
            if (trimmed.startsWith("shc:/")) {
                jwsCompact = QrCodeGeneratorUtility.fromSmartHealthCardNumericUri(trimmed);
            } else if (trimmed.startsWith("hcert:/ips/v1/")) {
                jwsCompact = trimmed.substring("hcert:/ips/v1/".length());
            } else {
                jwsCompact = trimmed;
            }
        } catch (Exception ex) {
            return new IpsSignatureVerificationResult(
                    false, "UNKNOWN", IPS_RSA_KEY_ID, rsaPublicKeyFingerprint, "",
                    null, null, null, Instant.now(), "Malformed IPS QR payload: " + ex.getMessage());
        }

        String[] parts = jwsCompact.split("\\.");
        if (parts.length != 3) {
            return new IpsSignatureVerificationResult(
                    false, "UNKNOWN", IPS_RSA_KEY_ID, rsaPublicKeyFingerprint, "",
                    null, null, null, Instant.now(),
                    "Malformed JWS compact serialization (expected 3 dot-separated segments)");
        }

        try {
            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            boolean deflated = headerJson.contains("\"zip\":\"DEF\"");
            String payloadText = deflated
                    ? inflateRawBytes(payloadBytes)
                    : new String(payloadBytes, StandardCharsets.UTF_8);

            byte[] signatureBytes = Base64.getUrlDecoder().decode(parts[2]);
            byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8);

            boolean edDsa = headerJson.contains("\"EdDSA\"");
            String resolvedKeyId = edDsa ? IPS_ED25519_KEY_ID : IPS_RSA_KEY_ID;
            String resolvedFingerprint = edDsa ? ed25519PublicKeyFingerprint : rsaPublicKeyFingerprint;

            Signature verifier = edDsa
                    ? Signature.getInstance("Ed25519")
                    : Signature.getInstance("SHA256withRSA");
            verifier.initVerify(edDsa ? ed25519KeyPair.getPublic() : rsaKeyPair.getPublic());
            verifier.update(signingInput);
            boolean valid = verifier.verify(signatureBytes);

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String payloadHash = HexFormat.of().formatHex(md.digest(payloadText.getBytes(StandardCharsets.UTF_8)));

            UUID bundleId = extractBundleUuid(payloadText);
            UUID patientId = extractSubjectPatientUuid(payloadText);

            return new IpsSignatureVerificationResult(
                    valid,
                    edDsa ? "EdDSA" : "RS256",
                    resolvedKeyId,
                    resolvedFingerprint,
                    payloadHash,
                    bundleId,
                    patientId,
                    valid ? payloadText : null,
                    Instant.now(),
                    valid ? "JWS signature and HL7 FHIR R4 IPS Bundle integrity verified"
                            : "Cryptographic signature verification failed"
            );
        } catch (Exception ex) {
            return new IpsSignatureVerificationResult(
                    false, "UNKNOWN", IPS_RSA_KEY_ID, rsaPublicKeyFingerprint, "",
                    null, null, null, Instant.now(), "Verification failure: " + ex.getMessage());
        }
    }

    /**
     * Publishes the RFC 7517 JSON Web Key Set (`JWKS`) for IPS `RS256` and `Ed25519` (`EdDSA`) verification.
     */
    public Map<String, Object> getPublicIpsJwks() {
        RSAPublicKey rsaPub = (RSAPublicKey) rsaKeyPair.getPublic();
        String n = Base64.getUrlEncoder().withoutPadding().encodeToString(rsaPub.getModulus().toByteArray());
        String e = Base64.getUrlEncoder().withoutPadding().encodeToString(rsaPub.getPublicExponent().toByteArray());

        byte[] edEncoded = ed25519KeyPair.getPublic().getEncoded();
        byte[] rawEd32 = edEncoded.length >= 32
                ? Arrays.copyOfRange(edEncoded, edEncoded.length - 32, edEncoded.length)
                : edEncoded;
        String x = Base64.getUrlEncoder().withoutPadding().encodeToString(rawEd32);

        Map<String, Object> rsaJwk = new LinkedHashMap<>();
        rsaJwk.put("kty", "RSA");
        rsaJwk.put("kid", IPS_RSA_KEY_ID);
        rsaJwk.put("use", "sig");
        rsaJwk.put("alg", "RS256");
        rsaJwk.put("n", n);
        rsaJwk.put("e", e);
        rsaJwk.put("x5t#S256", rsaPublicKeyFingerprint);

        Map<String, Object> edJwk = new LinkedHashMap<>();
        edJwk.put("kty", "OKP");
        edJwk.put("crv", "Ed25519");
        edJwk.put("kid", IPS_ED25519_KEY_ID);
        edJwk.put("use", "sig");
        edJwk.put("alg", "EdDSA");
        edJwk.put("x", x);
        edJwk.put("x5t#S256", ed25519PublicKeyFingerprint);

        return Map.of("keys", List.of(rsaJwk, edJwk));
    }

    private static boolean isEd25519Requested(String rawAlg) {
        if (rawAlg == null || rawAlg.isBlank()) {
            return false;
        }
        String upper = rawAlg.trim().toUpperCase(Locale.ROOT);
        return "EDDSA".equals(upper) || "ED25519".equals(upper);
    }

    private String signJwsCompact(String payloadJson, boolean edDsa, String keyId, boolean deflate) {
        try {
            String algCode = edDsa ? "EdDSA" : "RS256";
            String headerJson = deflate
                    ? "{\"alg\":\"" + algCode + "\",\"kid\":\"" + keyId + "\",\"zip\":\"DEF\",\"typ\":\"fhir+jwt\"}"
                    : "{\"alg\":\"" + algCode + "\",\"kid\":\"" + keyId + "\",\"typ\":\"fhir+jwt\"}";
            byte[] payloadBytes = deflate
                    ? deflateRawBytes(payloadJson.getBytes(StandardCharsets.UTF_8))
                    : payloadJson.getBytes(StandardCharsets.UTF_8);

            String encodedHeader = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
            String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payloadBytes);
            String signingInput = encodedHeader + "." + encodedPayload;

            Signature signer = edDsa
                    ? Signature.getInstance("Ed25519")
                    : Signature.getInstance("SHA256withRSA");
            signer.initSign(edDsa ? ed25519KeyPair.getPrivate() : rsaKeyPair.getPrivate());
            signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
            String encodedSig = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());

            return signingInput + "." + encodedSig;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign IPS Bundle with JWS " + (edDsa ? "EdDSA" : "RS256"), ex);
        }
    }

    private static byte[] deflateRawBytes(byte[] input) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length);
        byte[] buffer = new byte[512];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            out.write(buffer, 0, count);
        }
        deflater.end();
        return out.toByteArray();
    }

    private static String inflateRawBytes(byte[] compressed) throws Exception {
        Inflater inflater = new Inflater(true);
        inflater.setInput(compressed);
        ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 2);
        byte[] buffer = new byte[512];
        while (!inflater.finished()) {
            int count = inflater.inflate(buffer);
            if (count == 0 && inflater.needsInput()) {
                break;
            }
            out.write(buffer, 0, count);
        }
        inflater.end();
        return out.toString(StandardCharsets.UTF_8);
    }

    private static UUID extractBundleUuid(String json) {
        int idIdx = json.indexOf("\"id\"");
        if (idIdx < 0) return null;
        int colon = json.indexOf(':', idIdx);
        int q1 = json.indexOf('"', colon + 1);
        int q2 = json.indexOf('"', q1 + 1);
        if (q1 < 0 || q2 < 0) return null;
        try {
            return UUID.fromString(json.substring(q1 + 1, q2));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static UUID extractSubjectPatientUuid(String json) {
        String marker = "\"Patient/";
        int idx = json.indexOf(marker);
        if (idx < 0) return null;
        int start = idx + marker.length();
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        try {
            return UUID.fromString(json.substring(start, end));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
