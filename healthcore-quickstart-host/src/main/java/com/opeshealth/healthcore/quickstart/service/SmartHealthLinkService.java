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

import com.opeshealth.healthcore.quickstart.service.FhirAuditLoggingService.FhirAccessAuditCommand;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.service.PatientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HL7 SMART Health Links ({@code shlink:/}) Host, Encrypted Manifest, and Passcode Access Service.
 *
 * <p>Implements the SMART Health Links specification: generates AES-256-GCM symmetric keys,
 * encrypts attached clinical files (FHIR IPS Document Bundles, Digital Health Certificates,
 * Diagnostic Reports, or PDFs), builds compact {@code shlink:/} Base64URL payloads with
 * {@code L} (long-term live-updating) and {@code P} (passcode-protected) flags, enforces
 * expiration, maximum access counts, and revocation, and logs every manifest access into
 * the SHA-256 tamper-evident HIPAA/GDPR audit trail.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class SmartHealthLinkService {

    private static final Logger log = LoggerFactory.getLogger(SmartHealthLinkService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    public record CreateSmartHealthLinkRequest(
            UUID facilityId,
            UUID patientId,
            String label,
            String passcode,
            boolean longTermLiveUpdating,
            Integer expirationHours,
            Integer maxAccessCount,
            List<ShlFileInput> initialFiles
    ) {}

    public record ShlFileInput(
            String contentType,
            String fileName,
            String plainContent
    ) {}

    public record EncryptedShlFile(
            UUID fileId,
            String contentType,
            String fileName,
            String jweCompactCiphertext,
            String sha256PlainDigest,
            long plainSizeBytes,
            Instant addedAt
    ) {}

    public record SmartHealthLinkResponse(
            UUID shlId,
            UUID facilityId,
            UUID patientId,
            String label,
            String shlinkUri,
            String manifestUrl,
            String flags,
            String symmetricKeyBase64Url,
            boolean passcodeRequired,
            boolean longTermLiveUpdating,
            Instant createdAt,
            Instant expiresAt,
            Integer maxAccessCount,
            int accessCount,
            boolean revoked,
            int attachedFilesCount,
            String qrCodeSvg
    ) {}

    public record ShlManifestRequest(
            String recipient,
            String passcode,
            Integer embeddedLengthMax
    ) {}

    public record ShlManifestFileEntry(
            UUID fileId,
            String contentType,
            String location,
            String embeddedEncryptedPayload
    ) {}

    public record ShlManifestResponse(
            UUID shlId,
            String status,
            String label,
            String recipient,
            int accessCount,
            Instant expiresAt,
            List<ShlManifestFileEntry> files
    ) {}

    public record DecryptedShlFileResponse(
            UUID shlId,
            UUID fileId,
            String contentType,
            String fileName,
            String plainContent,
            boolean digestVerified
    ) {}

    private static final class ShlRecordState {
        final UUID shlId;
        final UUID facilityId;
        final UUID patientId;
        final String label;
        final String flags;
        final byte[] aesKeyBytes;
        final String symmetricKeyBase64Url;
        final String passcodeSalt;
        final String passcodeHash;
        final boolean longTerm;
        final Instant createdAt;
        final Instant expiresAt;
        final Integer maxAccessCount;
        final AtomicInteger accessCount = new AtomicInteger(0);
        volatile boolean revoked = false;
        volatile String revocationReason;
        final List<EncryptedShlFile> files = new CopyOnWriteArrayList<>();

        ShlRecordState(
                UUID shlId,
                UUID facilityId,
                UUID patientId,
                String label,
                String flags,
                byte[] aesKeyBytes,
                String symmetricKeyBase64Url,
                String passcodeSalt,
                String passcodeHash,
                boolean longTerm,
                Instant createdAt,
                Instant expiresAt,
                Integer maxAccessCount) {
            this.shlId = shlId;
            this.facilityId = facilityId;
            this.patientId = patientId;
            this.label = label;
            this.flags = flags;
            this.aesKeyBytes = aesKeyBytes;
            this.symmetricKeyBase64Url = symmetricKeyBase64Url;
            this.passcodeSalt = passcodeSalt;
            this.passcodeHash = passcodeHash;
            this.longTerm = longTerm;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.maxAccessCount = maxAccessCount;
        }
    }

    private final FhirAuditLoggingService fhirAuditLoggingService;
    private final Map<UUID, ShlRecordState> linksById = new ConcurrentHashMap<>();

    @Autowired
    public SmartHealthLinkService(@Autowired(required = false) FhirAuditLoggingService fhirAuditLoggingService) {
        this.fhirAuditLoggingService = fhirAuditLoggingService;
    }

    public SmartHealthLinkService() {
        this(null);
    }

    /**
     * Creates a new AES-256-GCM encrypted SMART Health Link ({@code shlink:/...}) with optional
     * passcode protection, expiration, max-access cap, and initial encrypted FHIR files.
     */
    @ClinicalAudit(action = "SHL_CREATE", resourceType = "DocumentReference")
    public SmartHealthLinkResponse createSmartHealthLink(CreateSmartHealthLinkRequest request, String baseUrl) {
        if (request == null || request.patientId() == null) {
            throw new IllegalArgumentException("CreateSmartHealthLinkRequest with patientId is required");
        }

        UUID shlId = UUID.randomUUID();
        UUID facilityId = request.facilityId() != null ? request.facilityId() : PatientService.DEFAULT_FACILITY_ID;
        String label = (request.label() != null && !request.label().isBlank())
                ? request.label().trim()
                : "Opes HealthCore Patient Health Summary";
        if (label.length() > 80) {
            label = label.substring(0, 80);
        }

        byte[] aesKey = generateAes256Key();
        String keyB64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(aesKey);

        boolean hasPasscode = request.passcode() != null && !request.passcode().isBlank();
        String salt = hasPasscode ? UUID.randomUUID().toString() : null;
        String passcodeHash = hasPasscode ? sha256Hex(salt + ":" + request.passcode().trim()) : null;

        StringBuilder flagBuilder = new StringBuilder();
        if (request.longTermLiveUpdating()) {
            flagBuilder.append('L');
        }
        if (hasPasscode) {
            flagBuilder.append('P');
        }
        String flags = flagBuilder.toString();

        Instant now = Instant.now();
        int expHours = (request.expirationHours() != null && request.expirationHours() > 0)
                ? request.expirationHours()
                : 72;
        Instant expiresAt = now.plus(Duration.ofHours(expHours));
        Integer maxAccess = (request.maxAccessCount() != null && request.maxAccessCount() > 0)
                ? request.maxAccessCount()
                : null;

        ShlRecordState state = new ShlRecordState(
                shlId, facilityId, request.patientId(), label, flags, aesKey, keyB64Url,
                salt, passcodeHash, request.longTermLiveUpdating(), now, expiresAt, maxAccess);

        if (request.initialFiles() != null) {
            for (ShlFileInput fileInput : request.initialFiles()) {
                if (fileInput != null && fileInput.plainContent() != null) {
                    state.files.add(encryptFileForShl(state, fileInput));
                }
            }
        }

        linksById.put(shlId, state);

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAccessAuditCommand(
                    facilityId.toString(),
                    "SHL_ISSUER",
                    "ROLE_CLINICIAN",
                    "SHL_CREATED",
                    "DocumentReference",
                    shlId.toString(),
                    request.patientId(),
                    "POST",
                    "/api/v1/shl",
                    "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "Created SMART Health Link flags=" + flags + " files=" + state.files.size()
            ));
        }

        return toResponse(state, baseUrl);
    }

    /**
     * Appends a new encrypted clinical file to an existing active SMART Health Link.
     */
    @ClinicalAudit(action = "SHL_ATTACH_FILE", resourceType = "DocumentReference")
    public EncryptedShlFile attachEncryptedFile(UUID shlId, ShlFileInput fileInput) {
        ShlRecordState state = requireActiveLink(shlId);
        if (fileInput == null || fileInput.plainContent() == null || fileInput.plainContent().isBlank()) {
            throw new IllegalArgumentException("ShlFileInput with non-blank plainContent is required");
        }
        EncryptedShlFile encrypted = encryptFileForShl(state, fileInput);
        state.files.add(encrypted);
        return encrypted;
    }

    /**
     * Evaluates an incoming SMART Health Link manifest request (`POST /api/v1/shl/{shlId}/manifest`),
     * validating passcode, expiration, revocation, and access limits, and returning encrypted file locations.
     */
    @ClinicalAudit(action = "SHL_MANIFEST_ACCESS", resourceType = "DocumentReference")
    public ShlManifestResponse resolveManifest(UUID shlId, ShlManifestRequest request, String baseUrl) {
        ShlRecordState state = linksById.get(shlId);
        if (state == null) {
            throw new NoSuchElementException("SMART Health Link not found: " + shlId);
        }
        String recipient = (request != null && request.recipient() != null && !request.recipient().isBlank())
                ? request.recipient().trim()
                : "UNKNOWN_RECIPIENT";

        if (state.revoked) {
            recordManifestAudit(state, recipient, "FAILURE", "Rejected access to revoked SMART Health Link");
            throw new IllegalStateException("SMART Health Link has been revoked");
        }
        if (Instant.now().isAfter(state.expiresAt)) {
            recordManifestAudit(state, recipient, "FAILURE", "Rejected access to expired SMART Health Link");
            throw new IllegalStateException("SMART Health Link has expired");
        }
        if (state.maxAccessCount != null && state.accessCount.get() >= state.maxAccessCount) {
            recordManifestAudit(state, recipient, "FAILURE", "Rejected SMART Health Link: maximum access count reached");
            throw new IllegalStateException("SMART Health Link maximum access limit reached");
        }
        if (state.passcodeHash != null) {
            String supplied = request != null ? request.passcode() : null;
            if (supplied == null || !state.passcodeHash.equals(sha256Hex(state.passcodeSalt + ":" + supplied.trim()))) {
                recordManifestAudit(state, recipient, "FAILURE", "Invalid SMART Health Link passcode");
                throw new SecurityException("Invalid SMART Health Link passcode");
            }
        }

        int currentAccess = state.accessCount.incrementAndGet();
        String resolvedBase = normalizeBaseUrl(baseUrl);
        int maxEmbed = (request != null && request.embeddedLengthMax() != null && request.embeddedLengthMax() > 0)
                ? request.embeddedLengthMax()
                : 0;

        List<ShlManifestFileEntry> entries = new ArrayList<>();
        for (EncryptedShlFile file : state.files) {
            String location = resolvedBase + "/api/v1/shl/" + shlId + "/files/" + file.fileId();
            String embedded = (maxEmbed > 0 && file.jweCompactCiphertext().length() <= maxEmbed)
                    ? file.jweCompactCiphertext()
                    : null;
            entries.add(new ShlManifestFileEntry(file.fileId(), file.contentType(), location, embedded));
        }

        recordManifestAudit(state, recipient, "SUCCESS",
                "Resolved SMART Health Link manifest (access #" + currentAccess + ", files=" + entries.size() + ")");

        return new ShlManifestResponse(
                shlId,
                state.longTerm ? "can-change" : "finalized",
                state.label,
                recipient,
                currentAccess,
                state.expiresAt,
                List.copyOf(entries)
        );
    }

    /**
     * Returns the AES-256-GCM encrypted JWE payload for a file attached to a SMART Health Link.
     */
    public EncryptedShlFile getEncryptedFile(UUID shlId, UUID fileId) {
        ShlRecordState state = requireActiveLink(shlId);
        return state.files.stream()
                .filter(f -> fileId.equals(f.fileId()))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Encrypted SHL file not found: " + fileId));
    }

    /**
     * Decrypts an encrypted SHL file using the provided Base64URL AES-256-GCM key from the {@code shlink:/} URI
     * and verifies its SHA-256 integrity digest.
     */
    public DecryptedShlFileResponse decryptFileWithKey(UUID shlId, UUID fileId, String symmetricKeyBase64Url) {
        if (symmetricKeyBase64Url == null || symmetricKeyBase64Url.isBlank()) {
            throw new IllegalArgumentException("symmetricKeyBase64Url is required to decrypt SMART Health Link file");
        }
        EncryptedShlFile file = getEncryptedFile(shlId, fileId);
        byte[] keyBytes = Base64.getUrlDecoder().decode(symmetricKeyBase64Url.trim());
        String plaintext = decryptCompactJweAesGcm(file.jweCompactCiphertext(), keyBytes);
        boolean digestOk = file.sha256PlainDigest().equals(sha256Hex(plaintext));
        return new DecryptedShlFileResponse(shlId, fileId, file.contentType(), file.fileName(), plaintext, digestOk);
    }

    /**
     * Revokes a SMART Health Link immediately so no further manifest or file fetches are permitted.
     */
    @ClinicalAudit(action = "SHL_REVOKE", resourceType = "DocumentReference")
    public SmartHealthLinkResponse revokeSmartHealthLink(UUID shlId, String reason, String baseUrl) {
        ShlRecordState state = linksById.get(shlId);
        if (state == null) {
            throw new NoSuchElementException("SMART Health Link not found: " + shlId);
        }
        state.revoked = true;
        state.revocationReason = (reason != null && !reason.isBlank()) ? reason.trim() : "Revoked by patient or clinician";
        recordManifestAudit(state, "SHL_REVOKER", "SUCCESS", "SMART Health Link revoked: " + state.revocationReason);
        return toResponse(state, baseUrl);
    }

    public List<SmartHealthLinkResponse> listPatientSmartHealthLinks(UUID patientId, String baseUrl) {
        return linksById.values().stream()
                .filter(s -> patientId == null || Objects.equals(patientId, s.patientId))
                .sorted(Comparator.comparing((ShlRecordState s) -> s.createdAt).reversed())
                .map(s -> toResponse(s, baseUrl))
                .toList();
    }

    private ShlRecordState requireActiveLink(UUID shlId) {
        ShlRecordState state = linksById.get(shlId);
        if (state == null) {
            throw new NoSuchElementException("SMART Health Link not found: " + shlId);
        }
        if (state.revoked) {
            throw new IllegalStateException("SMART Health Link is revoked: " + shlId);
        }
        if (Instant.now().isAfter(state.expiresAt)) {
            throw new IllegalStateException("SMART Health Link is expired: " + shlId);
        }
        return state;
    }

    private EncryptedShlFile encryptFileForShl(ShlRecordState state, ShlFileInput input) {
        String contentType = (input.contentType() != null && !input.contentType().isBlank())
                ? input.contentType().trim()
                : "application/fhir+json";
        String fileName = (input.fileName() != null && !input.fileName().isBlank())
                ? input.fileName().trim()
                : "ips-bundle.json";
        String plain = input.plainContent();
        String compactJwe = encryptCompactJweAesGcm(plain, contentType, state.aesKeyBytes);
        return new EncryptedShlFile(
                UUID.randomUUID(),
                contentType,
                fileName,
                compactJwe,
                sha256Hex(plain),
                plain.getBytes(StandardCharsets.UTF_8).length,
                Instant.now()
        );
    }

    private SmartHealthLinkResponse toResponse(ShlRecordState state, String baseUrl) {
        String resolvedBase = normalizeBaseUrl(baseUrl);
        String manifestUrl = resolvedBase + "/api/v1/shl/" + state.shlId + "/manifest";
        String shlPayloadJson = """
                {"url":"%s","key":"%s","flag":"%s","exp":%d,"label":"%s","v":1}
                """.formatted(
                manifestUrl,
                state.symmetricKeyBase64Url,
                state.flags,
                state.expiresAt.getEpochSecond(),
                state.label.replace("\"", "'")
        ).trim();
        String shlinkUri = "shlink:/" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(shlPayloadJson.getBytes(StandardCharsets.UTF_8));
        String qrSvg = DigitalHealthCertificateService.QrCodeGeneratorUtility
                .generateQrCodeAssets(shlinkUri, state.label)
                .qrCodeSvg();

        return new SmartHealthLinkResponse(
                state.shlId,
                state.facilityId,
                state.patientId,
                state.label,
                shlinkUri,
                manifestUrl,
                state.flags,
                state.symmetricKeyBase64Url,
                state.passcodeHash != null,
                state.longTerm,
                state.createdAt,
                state.expiresAt,
                state.maxAccessCount,
                state.accessCount.get(),
                state.revoked,
                state.files.size(),
                qrSvg
        );
    }

    private void recordManifestAudit(ShlRecordState state, String actor, String outcome, String detail) {
        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAccessAuditCommand(
                    state.facilityId.toString(),
                    actor,
                    "SHL_RECIPIENT",
                    "SHL_MANIFEST_ACCESS",
                    "DocumentReference",
                    state.shlId.toString(),
                    state.patientId,
                    "POST",
                    "/api/v1/shl/" + state.shlId + "/manifest",
                    "127.0.0.1",
                    null,
                    outcome,
                    0L,
                    detail
            ));
        }
    }

    private static byte[] generateAes256Key() {
        try {
            KeyGenerator gen = KeyGenerator.getInstance("AES");
            gen.init(256, SECURE_RANDOM);
            SecretKey key = gen.generateKey();
            return key.getEncoded();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate AES-256 SMART Health Link key", ex);
        }
    }

    private static String encryptCompactJweAesGcm(String plaintext, String contentType, byte[] keyBytes) {
        try {
            String protectedHeader = "{\"alg\":\"dir\",\"enc\":\"A256GCM\",\"cty\":\"" + contentType + "\"}";
            String encodedHeader = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(protectedHeader.getBytes(StandardCharsets.UTF_8));

            byte[] iv = new byte[GCM_IV_BYTES];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(encodedHeader.getBytes(StandardCharsets.US_ASCII));
            byte[] cipherPlusTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            int tagLen = GCM_TAG_BITS / 8;
            byte[] ciphertext = new byte[cipherPlusTag.length - tagLen];
            byte[] authTag = new byte[tagLen];
            System.arraycopy(cipherPlusTag, 0, ciphertext, 0, ciphertext.length);
            System.arraycopy(cipherPlusTag, ciphertext.length, authTag, 0, tagLen);

            Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
            return encodedHeader + ".." + b64.encodeToString(iv) + "."
                    + b64.encodeToString(ciphertext) + "." + b64.encodeToString(authTag);
        } catch (Exception ex) {
            throw new IllegalStateException("AES-256-GCM encryption failure for SMART Health Link", ex);
        }
    }

    private static String decryptCompactJweAesGcm(String compactJwe, byte[] keyBytes) {
        try {
            String[] parts = compactJwe.split("\\.");
            if (parts.length != 5) {
                throw new IllegalArgumentException("Malformed compact JWE payload");
            }
            Base64.Decoder b64 = Base64.getUrlDecoder();
            byte[] iv = b64.decode(parts[2]);
            byte[] ciphertext = b64.decode(parts[3]);
            byte[] authTag = b64.decode(parts[4]);

            byte[] combined = new byte[ciphertext.length + authTag.length];
            System.arraycopy(ciphertext, 0, combined, 0, ciphertext.length);
            System.arraycopy(authTag, 0, combined, ciphertext.length, authTag.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(parts[0].getBytes(StandardCharsets.US_ASCII));
            byte[] plainBytes = cipher.doFinal(combined);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.warn("Failed to decrypt SHL compact JWE payload: {}", ex.getMessage());
            throw new SecurityException("Failed to decrypt SMART Health Link payload with supplied key", ex);
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 failure", ex);
        }
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://localhost:3000";
        }
        String trimmed = baseUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
