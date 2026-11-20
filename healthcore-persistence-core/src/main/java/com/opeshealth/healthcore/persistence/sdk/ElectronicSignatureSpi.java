/*
 * =============================================================================
 * Opes HealthCore SDK — Domain-Neutral Electronic Signature & Maker-Checker SPI
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.persistence.sdk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Domain-neutral electronic signature contract supporting regulated healthcare,
 * pharmaceutical manufacturing (EU GMP Annex 11 / FDA 21 CFR Part 11 technical controls),
 * and life-sciences ELN/clinical-research sign-offs.
 *
 * <p>Implementers may plug in PKI/X.509, HSM, or OIDC step-up authentication providers
 * without modifying internal HealthCore starter services.</p>
 */
public interface ElectronicSignatureSpi {

    record ElectronicSignatureCommand(
            String signerId,
            String signerRole,
            String meaningOfSignature,
            String recordType,
            String recordId,
            String canonicalPayload,
            String creatorId,
            boolean enforceMakerChecker
    ) {}

    record ElectronicSignatureProof(
            String signerId,
            String signerRole,
            String meaningOfSignature,
            String payloadSha256,
            String signatureDigestSha256,
            Instant signedAt
    ) {}

    ElectronicSignatureProof sign(ElectronicSignatureCommand command);

    /**
     * Deterministic default electronic signature verifier enforcing non-blank signer identity,
     * meaning of signature, SHA-256 record binding, and optional maker-checker segregation of duties.
     */
    static ElectronicSignatureProof defaultSign(ElectronicSignatureCommand cmd) {
        Objects.requireNonNull(cmd, "ElectronicSignatureCommand is required");
        if (cmd.signerId() == null || cmd.signerId().isBlank()) {
            throw new IllegalArgumentException("Signer identity is required for electronic signature.");
        }
        if (cmd.meaningOfSignature() == null || cmd.meaningOfSignature().isBlank()) {
            throw new IllegalArgumentException("Meaning of signature (e.g. REVIEWED, APPROVED, RELEASED) is required.");
        }
        if (cmd.enforceMakerChecker() && cmd.creatorId() != null
                && cmd.creatorId().trim().equalsIgnoreCase(cmd.signerId().trim())) {
            throw new SecurityException(
                    "Maker-checker segregation of duties violation: signer '" + cmd.signerId()
                            + "' cannot approve/release their own record (" + cmd.recordType() + "/" + cmd.recordId() + ").");
        }
        Instant now = Instant.now();
        String payloadHash = sha256Hex(cmd.canonicalPayload() == null ? "" : cmd.canonicalPayload());
        String composite = cmd.signerId().trim() + "|" + cmd.signerRole() + "|" + cmd.meaningOfSignature().trim()
                + "|" + cmd.recordType() + "|" + cmd.recordId() + "|" + payloadHash + "|" + now;
        return new ElectronicSignatureProof(
                cmd.signerId().trim(),
                cmd.signerRole() == null ? "AUTHORIZED_SIGNER" : cmd.signerRole().trim(),
                cmd.meaningOfSignature().trim(),
                payloadHash,
                sha256Hex(composite),
                now
        );
    }

    static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
