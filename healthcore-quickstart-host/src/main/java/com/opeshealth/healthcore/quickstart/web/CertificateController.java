/*
 * Copyright (c) 2026 Opes Health Systems (https://www.opeshealthsystems.com)
 *
 * Author: Jude Nshome — Opes Health Systems
 * Email: nshome@opesware.com
 * Organization: Opes Health Systems, Douala, Cameroon
 *
 * Licensed under the Opes HealthCore Enterprise SDK License.
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.quickstart.service.DigitalHealthCertificateService;
import com.opeshealth.healthcore.quickstart.service.DigitalHealthCertificateService.CertificateIssuanceRequest;
import com.opeshealth.healthcore.quickstart.service.DigitalHealthCertificateService.CertificateVerificationRequest;
import com.opeshealth.healthcore.quickstart.service.DigitalHealthCertificateService.CertificateVerificationResult;
import com.opeshealth.healthcore.quickstart.service.DigitalHealthCertificateService.IssuedDigitalHealthCertificate;
import com.opeshealth.healthcore.quickstart.service.DigitalHealthCertificateService.QrCodeGeneratorUtility;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * REST Controller for JWS-Signed ({@code RS256} / {@code Ed25519}) Digital Health Certificates
 * and Mobile QR Code Verification.
 *
 * <p>Exposes endpoints for issuing cryptographically signed SMART Health Cards &amp; WHO GDHCN
 * health certificates, rendering SVG/PNG QR codes, verifying mobile QR scans, revoking
 * compromised certificates, and publishing RFC 7517 JWKS public keys.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/certificates")
@Tag(name = "FHIR R4 Digital Health Certificates & JWS QR Verification",
        description = "JWS signed (RS256 & Ed25519) health certificates, SMART Health Card (shc:/) QR code generation (SVG/PNG), mobile verification, and RFC 7517 JWKS")
public class CertificateController {

    public record CertificateRevocationRequest(
            String reason
    ) {}

    public record StandaloneQrRenderRequest(
            String payload,
            String label
    ) {}

    private final DigitalHealthCertificateService certificateService;

    public CertificateController(DigitalHealthCertificateService certificateService) {
        this.certificateService = Objects.requireNonNull(
                certificateService, "DigitalHealthCertificateService is required");
    }

    /**
     * Issues a JWS-signed ({@code RS256} or {@code Ed25519}/{@code EdDSA}) Digital Health Certificate
     * with embedded HL7 FHIR R4 credential bundle and mobile verification QR code assets.
     */
    @PostMapping(
            value = "/issue",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "Issue a JWS-signed (RS256 or Ed25519) Digital Health Certificate with QR code",
            description = "Signs an HL7 FHIR R4 Verifiable Credential bundle using RS256 or Ed25519 (EdDSA) and generates SMART Health Card (shc:/) numeric QR, SVG, and Base64 PNG assets."
    )
    public ResponseEntity<IssuedDigitalHealthCertificate> issueCertificate(
            @RequestHeader(value = "X-Facility-Id", required = false) UUID facilityHeader,
            @RequestBody CertificateIssuanceRequest request) {

        if (request == null) {
            throw new IllegalArgumentException("CertificateIssuanceRequest body is required");
        }
        CertificateIssuanceRequest effective = request.facilityId() != null || facilityHeader == null
                ? request
                : new CertificateIssuanceRequest(
                        facilityHeader,
                        request.patientId(),
                        request.certificateType(),
                        request.signingAlgorithm(),
                        request.clinicalCodeSystem(),
                        request.clinicalCode(),
                        request.clinicalDisplay(),
                        request.batchOrLotNumber(),
                        request.doseNumber(),
                        request.administeringCenterName(),
                        request.countryIsoCode(),
                        request.validityDays(),
                        request.compressPayloadDeflate()
                );

        return ResponseEntity.status(201).body(certificateService.issueCertificate(effective));
    }

    /**
     * Retrieves an issued Digital Health Certificate by its UUID.
     */
    @GetMapping(value = "/{certificateId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Retrieve an issued Digital Health Certificate by certificateId")
    public ResponseEntity<IssuedDigitalHealthCertificate> getCertificate(
            @PathVariable("certificateId") UUID certificateId) {
        return ResponseEntity.ok(certificateService.getCertificate(certificateId));
    }

    /**
     * Lists all Digital Health Certificates issued for a specific patient.
     */
    @GetMapping(value = "/patient/{patientId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List all Digital Health Certificates issued for a patient")
    public ResponseEntity<List<IssuedDigitalHealthCertificate>> listPatientCertificates(
            @PathVariable("patientId") UUID patientId) {
        return ResponseEntity.ok(certificateService.listPatientCertificates(patientId));
    }

    /**
     * Streams the scalable vector SVG QR code image for an issued certificate.
     */
    @GetMapping(value = "/{certificateId}/qr.svg", produces = "image/svg+xml")
    @Operation(summary = "Download the SVG QR code for a Digital Health Certificate")
    public ResponseEntity<String> getCertificateQrSvg(
            @PathVariable("certificateId") UUID certificateId) {
        IssuedDigitalHealthCertificate cert = certificateService.getCertificate(certificateId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("image/svg+xml"))
                .body(cert.qrCode().qrCodeSvg());
    }

    /**
     * Streams the binary PNG QR code image for an issued certificate.
     */
    @GetMapping(value = "/{certificateId}/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "Download the PNG QR code image for a Digital Health Certificate")
    public ResponseEntity<byte[]> getCertificateQrPng(
            @PathVariable("certificateId") UUID certificateId) {
        IssuedDigitalHealthCertificate cert = certificateService.getCertificate(certificateId);
        String dataUri = cert.qrCode().qrCodePngBase64DataUri();
        String base64Part = dataUri.startsWith("data:image/png;base64,")
                ? dataUri.substring("data:image/png;base64,".length())
                : dataUri;
        byte[] pngBytes = Base64.getDecoder().decode(base64Part);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(pngBytes);
    }

    /**
     * Generates a standalone mobile QR code (SVG and PNG Base64) for any JWS or clinical URI payload.
     */
    @PostMapping(
            value = "/qr/generate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(summary = "Generate SVG and PNG QR code assets for a JWS or mobile verification payload")
    public ResponseEntity<QrCodeGeneratorUtility.RenderedQrCode> generateStandaloneQrCode(
            @RequestBody StandaloneQrRenderRequest request) {
        if (request == null || request.payload() == null || request.payload().isBlank()) {
            throw new IllegalArgumentException("QR payload string is required");
        }
        return ResponseEntity.ok(QrCodeGeneratorUtility.generateQrCodeAssets(
                request.payload().trim(),
                request.label()
        ));
    }

    /**
     * Verifies a JWS compact signature or a scanned mobile QR payload ({@code shc:/...} or {@code hcert:/v1/...}).
     */
    @PostMapping(
            value = "/verify",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "Verify a Digital Health Certificate JWS signature or mobile QR scan payload",
            description = "Cryptographically verifies RS256 or Ed25519 (EdDSA) JWS compact tokens, shc:/ numeric QR URIs, and hcert:/v1/ QR strings, checking expiry and revocation status."
    )
    public ResponseEntity<CertificateVerificationResult> verifyCertificate(
            @RequestBody CertificateVerificationRequest request) {
        if (request == null || request.jwsOrQrPayload() == null || request.jwsOrQrPayload().isBlank()) {
            throw new IllegalArgumentException("jwsOrQrPayload is required");
        }
        return ResponseEntity.ok(certificateService.verifyCertificate(request.jwsOrQrPayload()));
    }

    /**
     * Revokes an issued Digital Health Certificate so future mobile scans report {@code REVOKED}.
     */
    @PostMapping(
            value = "/{certificateId}/revoke",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(summary = "Revoke an issued Digital Health Certificate")
    public ResponseEntity<IssuedDigitalHealthCertificate> revokeCertificate(
            @PathVariable("certificateId") UUID certificateId,
            @RequestParam(value = "reason", required = false) String reasonParam,
            @RequestBody(required = false) CertificateRevocationRequest body) {

        String reason = (body != null && body.reason() != null && !body.reason().isBlank())
                ? body.reason()
                : reasonParam;
        return ResponseEntity.ok(certificateService.revokeCertificate(certificateId, reason));
    }

    /**
     * Publishes the RFC 7517 JSON Web Key Set (`JWKS`) with the active `RS256` and `Ed25519`
     * public verification keys for offline mobile scanner applications.
     */
    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Retrieve RFC 7517 Public JSON Web Key Set (JWKS) for RS256 and Ed25519 offline mobile verification")
    public ResponseEntity<Map<String, Object>> getPublicJwks() {
        return ResponseEntity.ok(certificateService.getPublicJwks());
    }
}
