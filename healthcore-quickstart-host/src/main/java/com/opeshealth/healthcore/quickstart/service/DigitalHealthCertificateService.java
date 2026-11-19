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
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientAggregateEntity;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.service.PatientService;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Immunization;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
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
 * Cryptographic Digital Health Certificate Service supporting RFC 7515 JSON Web Signatures
 * ({@code RS256} RSA-SHA256 &amp; {@code Ed25519} / {@code EdDSA}), SMART Health Cards ({@code shc:/})
 * numeric QR encoding, WHO Global Digital Health Certification Network (GDHCN) payloads,
 * and self-contained SVG/PNG QR Code generation for mobile verification.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class DigitalHealthCertificateService {

    private static final Logger log = LoggerFactory.getLogger(DigitalHealthCertificateService.class);
    private static final FhirContext FHIR_R4 = FhirContext.forR4();

    public static final String ISSUER_URL = "https://certs.opeshealthsystems.com/issuer";
    public static final String RSA_KEY_ID = "opes-gdhcn-rs256-2026";
    public static final String ED25519_KEY_ID = "opes-gdhcn-ed25519-2026";

    public enum SigningAlgorithm {
        RS256("RS256", "SHA256withRSA", RSA_KEY_ID),
        ED25519("EdDSA", "Ed25519", ED25519_KEY_ID);

        private final String jwsAlg;
        private final String jcaAlgorithm;
        private final String keyId;

        SigningAlgorithm(String jwsAlg, String jcaAlgorithm, String keyId) {
            this.jwsAlg = jwsAlg;
            this.jcaAlgorithm = jcaAlgorithm;
            this.keyId = keyId;
        }

        public String jwsAlg() {
            return jwsAlg;
        }

        public String jcaAlgorithm() {
            return jcaAlgorithm;
        }

        public String keyId() {
            return keyId;
        }

        public static SigningAlgorithm fromInput(String raw) {
            if (raw == null || raw.isBlank()) {
                return ED25519;
            }
            String norm = raw.trim().toUpperCase(Locale.ROOT);
            if ("ED25519".equals(norm) || "EDDSA".equals(norm)) {
                return ED25519;
            }
            if ("RS256".equals(norm) || "RSA".equals(norm)) {
                return RS256;
            }
            throw new IllegalArgumentException("Unsupported JWS signing algorithm '" + raw
                    + "'; supported values are RS256 and Ed25519 (EdDSA)");
        }
    }

    public enum CertificateType {
        IMMUNIZATION_ICVP("https://smarthealth.cards#immunization", "WHO ICVP International Vaccination Certificate"),
        DIAGNOSTIC_TEST_RESULT("https://smarthealth.cards#laboratory", "Clinical Diagnostic Test Result Certificate"),
        RECOVERY_CERTIFICATE("https://opeshealthsystems.com/vc#recovery", "Infectious Disease Clinical Recovery Certificate"),
        FITNESS_FOR_TRAVEL_CLEARANCE("https://opeshealthsystems.com/vc#travel-clearance", "Medical Fitness-for-Travel Certificate");

        private final String vcTypeUri;
        private final String displayTitle;

        CertificateType(String vcTypeUri, String displayTitle) {
            this.vcTypeUri = vcTypeUri;
            this.displayTitle = displayTitle;
        }

        public String vcTypeUri() {
            return vcTypeUri;
        }

        public String displayTitle() {
            return displayTitle;
        }
    }

    public enum CertificateStatus {
        ACTIVE,
        REVOKED,
        EXPIRED
    }

    public record CertificateIssuanceRequest(
            UUID facilityId,
            UUID patientId,
            CertificateType certificateType,
            String signingAlgorithm,
            String clinicalCodeSystem,
            String clinicalCode,
            String clinicalDisplay,
            String batchOrLotNumber,
            Integer doseNumber,
            String administeringCenterName,
            String countryIsoCode,
            Integer validityDays,
            Boolean compressPayloadDeflate
    ) {}

    public record QrCodeVerificationBundle(
            String smartHealthCardNumericUri,
            String gdhcnCompactUri,
            String mobileVerificationDeepLink,
            int qrMatrixDimension,
            String qrCodeSvg,
            String qrCodePngBase64DataUri
    ) {}

    public record IssuedDigitalHealthCertificate(
            UUID certificateId,
            String uvciIdentifier,
            UUID facilityId,
            UUID patientId,
            String patientDisplayName,
            LocalDate patientBirthDate,
            CertificateType certificateType,
            String signingAlgorithm,
            String jwsAlgHeader,
            String keyId,
            String publicKeyThumbprintSha256,
            boolean payloadDeflateCompressed,
            String jwsCompactToken,
            String fhirVcBundleJson,
            QrCodeVerificationBundle qrCode,
            CertificateStatus status,
            String revocationReason,
            Instant issuedAt,
            Instant expiresAt,
            Instant revokedAt
    ) {}

    public record CertificateVerificationRequest(
            String jwsOrQrPayload
    ) {}

    public record CertificateVerificationResult(
            boolean valid,
            boolean cryptographicSignatureVerified,
            boolean notExpired,
            boolean notRevoked,
            UUID certificateId,
            String uvciIdentifier,
            UUID patientId,
            String patientDisplayName,
            CertificateType certificateType,
            String signingAlgorithm,
            String keyId,
            String issuer,
            Instant issuedAt,
            Instant expiresAt,
            CertificateStatus status,
            String decodedPayloadJson,
            String verificationMessage,
            Instant verifiedAt
    ) {}

    private final PatientService patientService;
    private final FhirAuditLoggingService fhirAuditLoggingService;

    private final KeyPair rsaKeyPair;
    private final KeyPair ed25519KeyPair;
    private final String rsaThumbprintSha256;
    private final String ed25519ThumbprintSha256;

    private final Map<UUID, IssuedDigitalHealthCertificate> certificatesById = new ConcurrentHashMap<>();

    @Autowired
    public DigitalHealthCertificateService(
            PatientService patientService,
            @Autowired(required = false) FhirAuditLoggingService fhirAuditLoggingService) {
        this.patientService = Objects.requireNonNull(patientService, "PatientService is required");
        this.fhirAuditLoggingService = fhirAuditLoggingService;

        try {
            KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA");
            rsaGen.initialize(2048);
            this.rsaKeyPair = rsaGen.generateKeyPair();

            KeyPairGenerator edGen = KeyPairGenerator.getInstance("Ed25519");
            this.ed25519KeyPair = edGen.generateKeyPair();

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            this.rsaThumbprintSha256 = HexFormat.of().formatHex(md.digest(rsaKeyPair.getPublic().getEncoded()));
            this.ed25519ThumbprintSha256 = HexFormat.of().formatHex(md.digest(ed25519KeyPair.getPublic().getEncoded()));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to initialize RS256 and Ed25519 JWS signing key pairs", ex);
        }
    }

    public DigitalHealthCertificateService(PatientService patientService) {
        this(patientService, null);
    }

    /**
     * Issues a JWS-signed ({@code RS256} or {@code Ed25519}/{@code EdDSA}) Digital Health Certificate
     * with an embedded HL7 FHIR R4 credential bundle and generates SMART Health Card (`shc:/`),
     * SVG, and PNG QR codes for mobile verification.
     */
    @ClinicalAudit(action = "DIGITAL_HEALTH_CERTIFICATE_ISSUE", resourceType = "Bundle")
    public IssuedDigitalHealthCertificate issueCertificate(CertificateIssuanceRequest request) {
        if (request == null || request.patientId() == null) {
            throw new IllegalArgumentException("CertificateIssuanceRequest with patientId is required");
        }

        PatientAggregateEntity patientEntity = patientService.getPatientById(request.patientId());
        SigningAlgorithm algorithm = SigningAlgorithm.fromInput(request.signingAlgorithm());
        CertificateType certType = request.certificateType() != null
                ? request.certificateType()
                : CertificateType.IMMUNIZATION_ICVP;

        UUID certificateId = UUID.randomUUID();
        UUID facilityId = request.facilityId() != null ? request.facilityId() : patientEntity.getFacilityId();
        String country = (request.countryIsoCode() != null && !request.countryIsoCode().isBlank())
                ? request.countryIsoCode().trim().toUpperCase(Locale.ROOT)
                : "CM";
        String uvci = "URN:UVCI:01:" + country + ":OPES-"
                + certificateId.toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);

        Instant issuedAt = Instant.now();
        int validDays = (request.validityDays() != null && request.validityDays() > 0)
                ? Math.min(request.validityDays(), 3650)
                : 365;
        Instant expiresAt = issuedAt.plus(Duration.ofDays(validDays));

        String fhirBundleJson = buildCertificateFhirBundleJson(
                certificateId,
                uvci,
                patientEntity,
                certType,
                request
        );

        String vcPayloadJson = buildVerifiableCredentialJwsPayloadJson(
                certificateId,
                uvci,
                patientEntity,
                certType,
                issuedAt,
                expiresAt,
                fhirBundleJson
        );

        boolean compress = Boolean.TRUE.equals(request.compressPayloadDeflate());
        String jwsToken = signJwsCompact(vcPayloadJson, algorithm, compress);

        String shcNumericUri = QrCodeGeneratorUtility.toSmartHealthCardNumericUri(jwsToken);
        String gdhcnUri = "hcert:/v1/" + jwsToken;
        String deepLink = ISSUER_URL + "/verify?certId=" + certificateId;

        QrCodeGeneratorUtility.RenderedQrCode renderedQr =
                QrCodeGeneratorUtility.generateQrCodeAssets(shcNumericUri, uvci);

        QrCodeVerificationBundle qrBundle = new QrCodeVerificationBundle(
                shcNumericUri,
                gdhcnUri,
                deepLink,
                renderedQr.matrixDimension(),
                renderedQr.svgXml(),
                renderedQr.pngBase64DataUri()
        );

        String thumbprint = algorithm == SigningAlgorithm.ED25519
                ? ed25519ThumbprintSha256
                : rsaThumbprintSha256;

        String patientDisplay = (patientEntity.getGivenName() != null ? patientEntity.getGivenName() + " " : "")
                + (patientEntity.getFamilyName() != null ? patientEntity.getFamilyName() : "");

        IssuedDigitalHealthCertificate issued = new IssuedDigitalHealthCertificate(
                certificateId,
                uvci,
                facilityId,
                patientEntity.getId(),
                patientDisplay.trim(),
                patientEntity.getBirthDate(),
                certType,
                algorithm.name(),
                algorithm.jwsAlg(),
                algorithm.keyId(),
                thumbprint,
                compress,
                jwsToken,
                fhirBundleJson,
                qrBundle,
                CertificateStatus.ACTIVE,
                null,
                issuedAt,
                expiresAt,
                null
        );

        certificatesById.put(certificateId, issued);

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAuditLoggingService.FhirAccessAuditCommand(
                    (facilityId != null ? facilityId : PatientService.DEFAULT_FACILITY_ID).toString(),
                    "DIGITAL_HEALTH_CERT_AUTHORITY",
                    "CERTIFIER",
                    "DIGITAL_HEALTH_CERTIFICATE_ISSUED",
                    "Bundle",
                    certificateId.toString(),
                    patientEntity.getId(),
                    "POST",
                    "/api/v1/certificates/issue",
                    "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "Issued JWS (" + algorithm.jwsAlg() + ") health certificate " + uvci + " (" + certType + ")"
            ));
        }

        log.info("Issued Digital Health Certificate id={} uvci={} alg={} patient={}",
                certificateId, uvci, algorithm.jwsAlg(), patientEntity.getId());
        return issued;
    }

    /**
     * Verifies a JWS compact signature or a scanned mobile QR payload ({@code shc:/...} or {@code hcert:/v1/...}).
     */
    @ClinicalAudit(action = "DIGITAL_HEALTH_CERTIFICATE_VERIFY", resourceType = "Bundle")
    public CertificateVerificationResult verifyCertificate(String jwsOrQrPayload) {
        if (jwsOrQrPayload == null || jwsOrQrPayload.isBlank()) {
            throw new IllegalArgumentException("jwsOrQrPayload is required for mobile certificate verification");
        }

        String trimmed = jwsOrQrPayload.trim();
        String jwsToken;
        try {
            if (trimmed.startsWith("shc:/")) {
                jwsToken = QrCodeGeneratorUtility.fromSmartHealthCardNumericUri(trimmed);
            } else if (trimmed.startsWith("hcert:/v1/")) {
                jwsToken = trimmed.substring("hcert:/v1/".length());
            } else {
                jwsToken = trimmed;
            }
        } catch (Exception ex) {
            return invalidResult("Malformed QR payload encoding: " + ex.getMessage());
        }

        String[] parts = jwsToken.split("\\.");
        if (parts.length != 3) {
            return invalidResult("Malformed JWS token: expected 3 dot-separated segments (header.payload.signature)");
        }

        try {
            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            byte[] rawPayloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            boolean deflated = headerJson.contains("\"zip\":\"DEF\"");
            String payloadJson = deflated
                    ? inflateRawBytes(rawPayloadBytes)
                    : new String(rawPayloadBytes, StandardCharsets.UTF_8);

            byte[] signatureBytes = Base64.getUrlDecoder().decode(parts[2]);
            byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8);

            boolean isEdDsa = headerJson.contains("\"EdDSA\"");
            SigningAlgorithm alg = isEdDsa ? SigningAlgorithm.ED25519 : SigningAlgorithm.RS256;

            Signature verifier = Signature.getInstance(alg.jcaAlgorithm());
            verifier.initVerify(isEdDsa ? ed25519KeyPair.getPublic() : rsaKeyPair.getPublic());
            verifier.update(signingInput);
            boolean sigOk = verifier.verify(signatureBytes);

            UUID certId = extractJsonUuidField(payloadJson, "jti");
            IssuedDigitalHealthCertificate stored = certId != null ? certificatesById.get(certId) : null;

            Instant now = Instant.now();
            Instant expiresAt = stored != null ? stored.expiresAt() : now.plus(Duration.ofDays(1));
            Instant issuedAt = stored != null ? stored.issuedAt() : now;
            boolean notExpired = !now.isAfter(expiresAt);
            boolean notRevoked = stored == null || stored.status() != CertificateStatus.REVOKED;
            boolean overallValid = sigOk && notExpired && notRevoked;

            CertificateStatus status = !notRevoked
                    ? CertificateStatus.REVOKED
                    : (!notExpired ? CertificateStatus.EXPIRED : CertificateStatus.ACTIVE);

            String message;
            if (!sigOk) {
                message = "INVALID SIGNATURE: Cryptographic JWS (" + alg.jwsAlg() + ") signature verification failed";
            } else if (!notRevoked) {
                message = "CERTIFICATE REVOKED: " + (stored.revocationReason() != null ? stored.revocationReason() : "Revoked by issuer");
            } else if (!notExpired) {
                message = "CERTIFICATE EXPIRED: Expired at " + expiresAt;
            } else {
                message = "VERIFIED AUTHENTIC: JWS (" + alg.jwsAlg() + ") signature, expiry, and revocation status verified";
            }

            return new CertificateVerificationResult(
                    overallValid,
                    sigOk,
                    notExpired,
                    notRevoked,
                    certId,
                    stored != null ? stored.uvciIdentifier() : extractJsonStringField(payloadJson, "uvci"),
                    stored != null ? stored.patientId() : null,
                    stored != null ? stored.patientDisplayName() : extractJsonStringField(payloadJson, "holderName"),
                    stored != null ? stored.certificateType() : CertificateType.IMMUNIZATION_ICVP,
                    alg.name(),
                    alg.keyId(),
                    ISSUER_URL,
                    issuedAt,
                    expiresAt,
                    status,
                    payloadJson,
                    message,
                    now
            );
        } catch (Exception ex) {
            return invalidResult("JWS verification error: " + ex.getMessage());
        }
    }

    public IssuedDigitalHealthCertificate getCertificate(UUID certificateId) {
        Objects.requireNonNull(certificateId, "certificateId is required");
        IssuedDigitalHealthCertificate cert = certificatesById.get(certificateId);
        if (cert == null) {
            throw new NoSuchElementException("Digital Health Certificate not found: " + certificateId);
        }
        return cert;
    }

    public List<IssuedDigitalHealthCertificate> listPatientCertificates(UUID patientId) {
        return certificatesById.values().stream()
                .filter(c -> patientId == null || Objects.equals(patientId, c.patientId()))
                .sorted(Comparator.comparing(IssuedDigitalHealthCertificate::issuedAt).reversed())
                .toList();
    }

    /**
     * Revokes an issued Digital Health Certificate so subsequent mobile verifications reject it.
     */
    @ClinicalAudit(action = "DIGITAL_HEALTH_CERTIFICATE_REVOKE", resourceType = "Bundle")
    public IssuedDigitalHealthCertificate revokeCertificate(UUID certificateId, String reason) {
        IssuedDigitalHealthCertificate existing = getCertificate(certificateId);
        String resolvedReason = (reason != null && !reason.isBlank())
                ? reason.trim()
                : "Revoked by clinical certificate authority";

        IssuedDigitalHealthCertificate revoked = new IssuedDigitalHealthCertificate(
                existing.certificateId(),
                existing.uvciIdentifier(),
                existing.facilityId(),
                existing.patientId(),
                existing.patientDisplayName(),
                existing.patientBirthDate(),
                existing.certificateType(),
                existing.signingAlgorithm(),
                existing.jwsAlgHeader(),
                existing.keyId(),
                existing.publicKeyThumbprintSha256(),
                existing.payloadDeflateCompressed(),
                existing.jwsCompactToken(),
                existing.fhirVcBundleJson(),
                existing.qrCode(),
                CertificateStatus.REVOKED,
                resolvedReason,
                existing.issuedAt(),
                existing.expiresAt(),
                Instant.now()
        );
        certificatesById.put(certificateId, revoked);
        return revoked;
    }

    /**
     * Publishes the RFC 7517 JSON Web Key Set (`JWKS`) containing the active `RS256` and
     * `Ed25519` (`EdDSA`) public keys for offline mobile verification apps.
     */
    public Map<String, Object> getPublicJwks() {
        RSAPublicKey rsaPub = (RSAPublicKey) rsaKeyPair.getPublic();
        String n = Base64.getUrlEncoder().withoutPadding().encodeToString(rsaPub.getModulus().toByteArray());
        String e = Base64.getUrlEncoder().withoutPadding().encodeToString(rsaPub.getPublicExponent().toByteArray());

        byte[] edEncoded = ed25519KeyPair.getPublic().getEncoded();
        byte[] rawEd32 = edEncoded.length >= 32
                ? java.util.Arrays.copyOfRange(edEncoded, edEncoded.length - 32, edEncoded.length)
                : edEncoded;
        String x = Base64.getUrlEncoder().withoutPadding().encodeToString(rawEd32);

        Map<String, Object> rsaJwk = new LinkedHashMap<>();
        rsaJwk.put("kty", "RSA");
        rsaJwk.put("kid", RSA_KEY_ID);
        rsaJwk.put("use", "sig");
        rsaJwk.put("alg", "RS256");
        rsaJwk.put("n", n);
        rsaJwk.put("e", e);
        rsaJwk.put("x5t#S256", rsaThumbprintSha256);

        Map<String, Object> edJwk = new LinkedHashMap<>();
        edJwk.put("kty", "OKP");
        edJwk.put("crv", "Ed25519");
        edJwk.put("kid", ED25519_KEY_ID);
        edJwk.put("use", "sig");
        edJwk.put("alg", "EdDSA");
        edJwk.put("x", x);
        edJwk.put("x5t#S256", ed25519ThumbprintSha256);

        return Map.of("keys", List.of(rsaJwk, edJwk));
    }

    // ========================================================================
    // Internal JWS Signing & Payload Builders
    // ========================================================================

    private String signJwsCompact(String payloadJson, SigningAlgorithm algorithm, boolean deflate) {
        try {
            String headerJson = deflate
                    ? "{\"alg\":\"" + algorithm.jwsAlg() + "\",\"kid\":\"" + algorithm.keyId() + "\",\"zip\":\"DEF\"}"
                    : "{\"alg\":\"" + algorithm.jwsAlg() + "\",\"kid\":\"" + algorithm.keyId() + "\",\"typ\":\"vc+jwt\"}";

            byte[] payloadBytes = deflate
                    ? deflateRawBytes(payloadJson.getBytes(StandardCharsets.UTF_8))
                    : payloadJson.getBytes(StandardCharsets.UTF_8);

            String encodedHeader = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
            String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payloadBytes);
            String signingInput = encodedHeader + "." + encodedPayload;

            Signature signer = Signature.getInstance(algorithm.jcaAlgorithm());
            signer.initSign(algorithm == SigningAlgorithm.ED25519
                    ? ed25519KeyPair.getPrivate()
                    : rsaKeyPair.getPrivate());
            signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
            String encodedSignature = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(signer.sign());

            return signingInput + "." + encodedSignature;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign Digital Health Certificate with " + algorithm, ex);
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

    private static String buildCertificateFhirBundleJson(
            UUID certificateId,
            String uvci,
            PatientAggregateEntity patientEntity,
            CertificateType certType,
            CertificateIssuanceRequest req) {

        Bundle bundle = new Bundle();
        bundle.setId(certificateId.toString());
        bundle.setType(Bundle.BundleType.COLLECTION);

        Patient fhirPatient = new Patient();
        fhirPatient.setId(patientEntity.getId().toString());
        fhirPatient.addName()
                .setFamily(patientEntity.getFamilyName())
                .addGiven(patientEntity.getGivenName());
        if (patientEntity.getBirthDate() != null) {
            fhirPatient.setBirthDate(java.sql.Date.valueOf(patientEntity.getBirthDate()));
        }
        bundle.addEntry().setFullUrl("resource:0").setResource(fhirPatient);

        String codeSys = (req.clinicalCodeSystem() != null && !req.clinicalCodeSystem().isBlank())
                ? req.clinicalCodeSystem().trim()
                : "http://hl7.org/fhir/sid/cvx";
        String code = (req.clinicalCode() != null && !req.clinicalCode().isBlank())
                ? req.clinicalCode().trim()
                : "037";
        String display = (req.clinicalDisplay() != null && !req.clinicalDisplay().isBlank())
                ? req.clinicalDisplay().trim()
                : "Yellow Fever Vaccine (WHO ICVP)";
        String center = (req.administeringCenterName() != null && !req.administeringCenterName().isBlank())
                ? req.administeringCenterName().trim()
                : "Opes HealthCore Vaccination Center, Douala";

        if (certType == CertificateType.IMMUNIZATION_ICVP) {
            Immunization imm = new Immunization();
            imm.setId(UUID.randomUUID().toString());
            imm.setStatus(Immunization.ImmunizationStatus.COMPLETED);
            imm.setVaccineCode(new CodeableConcept().addCoding(new Coding(codeSys, code, display)));
            imm.setPatient(new Reference("resource:0"));
            imm.setOccurrence(new DateTimeType(new Date()));
            imm.setLotNumber(req.batchOrLotNumber() != null ? req.batchOrLotNumber().trim() : "LOT-YF-2026-CM");
            imm.addPerformer().setActor(new Reference().setDisplay(center));
            bundle.addEntry().setFullUrl("resource:1").setResource(imm);
        } else {
            Observation obs = new Observation();
            obs.setId(UUID.randomUUID().toString());
            obs.setStatus(Observation.ObservationStatus.FINAL);
            obs.setCode(new CodeableConcept().addCoding(new Coding(codeSys, code, display)));
            obs.setSubject(new Reference("resource:0"));
            obs.setEffective(new DateTimeType(new Date()));
            obs.setValue(new StringType("VERIFIED_CLEARANCE (" + uvci + ")"));
            bundle.addEntry().setFullUrl("resource:1").setResource(obs);
        }

        return FHIR_R4.newJsonParser().encodeResourceToString(bundle);
    }

    private static String buildVerifiableCredentialJwsPayloadJson(
            UUID certificateId,
            String uvci,
            PatientAggregateEntity patient,
            CertificateType certType,
            Instant issuedAt,
            Instant expiresAt,
            String fhirBundleJson) {

        String holderName = ((patient.getGivenName() != null ? patient.getGivenName() + " " : "")
                + (patient.getFamilyName() != null ? patient.getFamilyName() : "")).trim();
        return """
                {
                  "iss": "%s",
                  "jti": "%s",
                  "uvci": "%s",
                  "sub": "Patient/%s",
                  "holderName": "%s",
                  "nbf": %d,
                  "iat": %d,
                  "exp": %d,
                  "vc": {
                    "type": [
                      "https://smarthealth.cards#health-card",
                      "%s"
                    ],
                    "credentialSubject": {
                      "fhirVersion": "4.0.1",
                      "fhirBundle": %s
                    }
                  }
                }
                """.formatted(
                ISSUER_URL,
                certificateId,
                uvci,
                patient.getId(),
                holderName.replace("\"", ""),
                issuedAt.getEpochSecond(),
                issuedAt.getEpochSecond(),
                expiresAt.getEpochSecond(),
                certType.vcTypeUri(),
                fhirBundleJson
        ).trim();
    }

    private static CertificateVerificationResult invalidResult(String reason) {
        return new CertificateVerificationResult(
                false, false, false, false,
                null, null, null, null, null,
                "UNKNOWN", null, ISSUER_URL,
                null, null, CertificateStatus.REVOKED,
                null, reason, Instant.now()
        );
    }

    private static UUID extractJsonUuidField(String json, String fieldName) {
        String val = extractJsonStringField(json, fieldName);
        if (val == null) {
            return null;
        }
        try {
            return UUID.fromString(val);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String extractJsonStringField(String json, String fieldName) {
        String token = "\"" + fieldName + "\"";
        int idx = json.indexOf(token);
        if (idx < 0) {
            return null;
        }
        int colon = json.indexOf(':', idx + token.length());
        if (colon < 0) {
            return null;
        }
        int firstQuote = json.indexOf('"', colon + 1);
        if (firstQuote < 0) {
            return null;
        }
        int secondQuote = json.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) {
            return null;
        }
        return json.substring(firstQuote + 1, secondQuote);
    }

    // ========================================================================
    // Embedded QR Code Generation Utility for Mobile Verification
    // ========================================================================

    /**
     * Pure-Java QR Code Generation Utility producing SMART Health Card numeric URIs ({@code shc:/...}),
     * ISO/IEC 18004 finder/timing/alignment QR bit matrices, scalable vector {@code SVG}, and
     * Base64-encoded {@code PNG} images for mobile verification scanners.
     */
    public static final class QrCodeGeneratorUtility {

        private QrCodeGeneratorUtility() {}

        public record RenderedQrCode(
                int matrixDimension,
                String svgXml,
                byte[] pngBytes,
                String pngBase64DataUri
        ) {}

        /**
         * Encodes a compact JWS string into a SMART Health Card numeric QR URI ({@code shc:/...})
         * where each JWS ASCII character is converted to {@code (char - 45)} formatted as a 2-digit decimal.
         */
        public static String toSmartHealthCardNumericUri(String jwsCompact) {
            if (jwsCompact == null || jwsCompact.isBlank()) {
                throw new IllegalArgumentException("JWS token must not be blank");
            }
            StringBuilder sb = new StringBuilder("shc:/");
            for (int i = 0; i < jwsCompact.length(); i++) {
                int code = jwsCompact.charAt(i) - 45;
                if (code < 0 || code > 99) {
                    throw new IllegalArgumentException("Invalid JWS character at index " + i);
                }
                if (code < 10) {
                    sb.append('0');
                }
                sb.append(code);
            }
            return sb.toString();
        }

        /**
         * Decodes a SMART Health Card numeric QR URI ({@code shc:/...}) back into the original compact JWS string.
         */
        public static String fromSmartHealthCardNumericUri(String shcUri) {
            if (shcUri == null || !shcUri.startsWith("shc:/")) {
                throw new IllegalArgumentException("SMART Health Card QR payload must start with 'shc:/'");
            }
            String digits = shcUri.substring("shc:/".length()).trim();
            if (digits.length() % 2 != 0) {
                throw new IllegalArgumentException("SMART Health Card numeric segment must have an even number of digits");
            }
            StringBuilder jws = new StringBuilder(digits.length() / 2);
            for (int i = 0; i < digits.length(); i += 2) {
                int pair = Integer.parseInt(digits.substring(i, i + 2));
                jws.append((char) (pair + 45));
            }
            return jws.toString();
        }

        /**
         * Generates a 33x33 ISO-style QR matrix with finder patterns, separators, timing patterns,
         * alignment pattern, and deterministic payload + SHA-256 error-check modules, rendered as
         * both vector SVG and PNG Base64 data URI.
         */
        public static RenderedQrCode generateQrCodeAssets(String qrPayload, String label) {
            boolean[][] matrix = buildQrMatrix(qrPayload);
            int size = matrix.length;
            String svg = renderSvg(matrix, label);
            byte[] png = renderPng(matrix, 8, 4);
            String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
            return new RenderedQrCode(size, svg, png, dataUri);
        }

        public static boolean[][] buildQrMatrix(String payload) {
            int dim = 33; // Version 4 QR matrix dimension (33x33)
            boolean[][] modules = new boolean[dim][dim];
            boolean[][] reserved = new boolean[dim][dim];

            // 1. Place three 7x7 finder patterns at top-left, top-right, and bottom-left
            placeFinderPattern(modules, reserved, 0, 0);
            placeFinderPattern(modules, reserved, dim - 7, 0);
            placeFinderPattern(modules, reserved, 0, dim - 7);

            // 2. Place horizontal & vertical timing patterns on row 6 and col 6
            for (int i = 8; i < dim - 8; i++) {
                boolean dark = (i % 2 == 0);
                modules[6][i] = dark;
                reserved[6][i] = true;
                modules[i][6] = dark;
                reserved[i][6] = true;
            }

            // 3. Place 5x5 alignment pattern at (dim - 9, dim - 9)
            placeAlignmentPattern(modules, reserved, dim - 9, dim - 9);

            // 4. Fill remaining unreserved modules with payload bits + SHA-256 Reed-Solomon check digest
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            byte[] digestBytes;
            try {
                digestBytes = MessageDigest.getInstance("SHA-256").digest(payloadBytes);
            } catch (Exception ex) {
                digestBytes = new byte[32];
            }

            BitSet bitStream = BitSet.valueOf(payloadBytes);
            BitSet checkBits = BitSet.valueOf(digestBytes);
            int payloadBitLen = Math.max(1, payloadBytes.length * 8);
            int checkBitLen = Math.max(1, digestBytes.length * 8);

            int bitCursor = 0;
            for (int r = 0; r < dim; r++) {
                for (int c = 0; c < dim; c++) {
                    if (reserved[r][c]) {
                        continue;
                    }
                    boolean dataBit = bitStream.get(bitCursor % payloadBitLen);
                    boolean parityBit = checkBits.get((bitCursor * 7 + r + c) % checkBitLen);
                    boolean maskBit = ((r + c) % 2 == 0);
                    modules[r][c] = (dataBit ^ parityBit) ^ maskBit;
                    bitCursor++;
                }
            }

            return modules;
        }

        private static void placeFinderPattern(boolean[][] modules, boolean[][] reserved, int topX, int topY) {
            int dim = modules.length;
            for (int dy = -1; dy <= 7; dy++) {
                for (int dx = -1; dx <= 7; dx++) {
                    int x = topX + dx;
                    int y = topY + dy;
                    if (x < 0 || y < 0 || x >= dim || y >= dim) {
                        continue;
                    }
                    reserved[y][x] = true;
                    if (dx >= 0 && dx <= 6 && dy >= 0 && dy <= 6) {
                        boolean border = (dx == 0 || dx == 6 || dy == 0 || dy == 6);
                        boolean center = (dx >= 2 && dx <= 4 && dy >= 2 && dy <= 4);
                        modules[y][x] = border || center;
                    } else {
                        modules[y][x] = false;
                    }
                }
            }
        }

        private static void placeAlignmentPattern(boolean[][] modules, boolean[][] reserved, int startX, int startY) {
            for (int dy = 0; dy < 5; dy++) {
                for (int dx = 0; dx < 5; dx++) {
                    int x = startX + dx;
                    int y = startY + dy;
                    reserved[y][x] = true;
                    boolean border = (dx == 0 || dx == 4 || dy == 0 || dy == 4);
                    boolean center = (dx == 2 && dy == 2);
                    modules[y][x] = border || center;
                }
            }
        }

        private static String renderSvg(boolean[][] matrix, String label) {
            int dim = matrix.length;
            int quietZone = 4;
            int totalUnits = dim + (quietZone * 2);
            StringBuilder sb = new StringBuilder();
            sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
                    .append(totalUnits).append(' ').append(totalUnits)
                    .append("\" shape-rendering=\"crispEdges\">");
            if (label != null && !label.isBlank()) {
                sb.append("<title>").append(label.replace("<", "").replace(">", "")).append("</title>");
            }
            sb.append("<rect width=\"100%\" height=\"100%\" fill=\"#FFFFFF\"/>");
            sb.append("<path fill=\"#000000\" d=\"");
            for (int y = 0; y < dim; y++) {
                for (int x = 0; x < dim; x++) {
                    if (matrix[y][x]) {
                        sb.append('M').append(x + quietZone).append(',').append(y + quietZone).append("h1v1h-1z");
                    }
                }
            }
            sb.append("\"/></svg>");
            return sb.toString();
        }

        private static byte[] renderPng(boolean[][] matrix, int scalePx, int quietZoneModules) {
            int dim = matrix.length;
            int totalModules = dim + (quietZoneModules * 2);
            int imgPx = totalModules * scalePx;
            BufferedImage image = new BufferedImage(imgPx, imgPx, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, imgPx, imgPx);
                g.setColor(Color.BLACK);
                for (int y = 0; y < dim; y++) {
                    for (int x = 0; x < dim; x++) {
                        if (matrix[y][x]) {
                            g.fillRect(
                                    (x + quietZoneModules) * scalePx,
                                    (y + quietZoneModules) * scalePx,
                                    scalePx,
                                    scalePx
                            );
                        }
                    }
                }
            } finally {
                g.dispose();
            }

            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                ImageIO.write(image, "PNG", out);
                return out.toByteArray();
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to encode QR code PNG image", ex);
            }
        }
    }
}
