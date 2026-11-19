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

import com.opeshealth.healthcore.quickstart.service.InternationalPatientSummaryService;
import com.opeshealth.healthcore.quickstart.service.InternationalPatientSummaryService.IpsGenerationOptionsRequest;
import com.opeshealth.healthcore.quickstart.service.InternationalPatientSummaryService.IpsSeedClinicalSummaryRequest;
import com.opeshealth.healthcore.quickstart.service.InternationalPatientSummaryService.IpsSignatureVerificationResult;
import com.opeshealth.healthcore.quickstart.service.InternationalPatientSummaryService.SignedIpsBundleResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * HL7 FHIR R4 International Patient Summary ({@code Patient/{id}/$summary}) &amp;
 * JWS-Signed ({@code RS256} / {@code Ed25519}) QR Health Record REST Controller.
 *
 * <p>Exposes endpoints for generating JWS-signed FHIR IPS Document Bundles (`BundleType.DOCUMENT`),
 * seeding clinical sections (problems, allergies, medications, immunizations, procedures),
 * streaming scalable vector `SVG` and `PNG` QR code images (`shc:/`, `hcert:/ips/v1/`, `shlink:/`),
 * verifying JWS signatures or mobile QR scans, and publishing RFC 7517 public JWKS keys.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@Tag(name = "FHIR R4 International Patient Summary ($summary) & JWS QR Payloads",
        description = "HL7 FHIR R4 Patient/{id}/$summary operation generating JWS-signed (RS256 & Ed25519) IPS Document Bundles, SMART Health Card (shc:/) QR payloads, SVG/PNG QR codes, and cryptographic verification")
public class InternationalPatientSummaryController {

    private static final String FHIR_JSON = "application/fhir+json";

    public record IpsVerificationPayloadRequest(
            String jwsOrQrPayload
    ) {}

    private final InternationalPatientSummaryService ipsService;

    public InternationalPatientSummaryController(InternationalPatientSummaryService ipsService) {
        this.ipsService = Objects.requireNonNull(
                ipsService, "InternationalPatientSummaryService is required");
    }

    /**
     * Standard HL7 FHIR R4 {@code GET /api/v1/fhir/Patient/{id}/$summary} and
     * {@code GET /api/v1/ips/Patient/{id}/$summary} operation generating a JWS-signed
     * ({@code RS256} or {@code Ed25519}/{@code EdDSA}) IPS Document Bundle and associated QR code payloads.
     */
    @GetMapping(
            value = {"/api/v1/fhir/Patient/{id}/$summary", "/api/v1/ips/Patient/{id}/$summary"},
            produces = {MediaType.APPLICATION_JSON_VALUE, FHIR_JSON}
    )
    @Operation(
            summary = "Generate a JWS-signed HL7 FHIR R4 International Patient Summary (Patient/{id}/$summary)",
            description = "Assembles an IPS Document Bundle (Composition LOINC 60591-5, Patient, Condition, AllergyIntolerance, MedicationStatement, Immunization, Procedure, Observation), applies DS4P privacy rules, signs with RS256 or Ed25519 (EdDSA), and generates shc:/, shlink:/, SVG, and PNG QR code payloads."
    )
    public ResponseEntity<SignedIpsBundleResponse> generatePatientIpsSummary(
            @PathVariable("id") UUID patientId,
            @RequestHeader(value = "X-Actor-Id", required = false) String actorHeader,
            @RequestHeader(value = "X-Break-The-Glass-Token", required = false) String btgTokenHeader,
            @RequestParam(value = "breakTheGlassToken", required = false) String btgTokenParam,
            @RequestParam(value = "alg", defaultValue = "RS256") String jwsAlgorithm,
            @RequestParam(value = "deflate", defaultValue = "false") boolean deflatePayload) {

        String resolvedBtg = (btgTokenParam != null && !btgTokenParam.isBlank()) ? btgTokenParam : btgTokenHeader;
        SignedIpsBundleResponse response = ipsService.generateSignedPatientSummary(new IpsGenerationOptionsRequest(
                patientId,
                actorHeader,
                resolvedBtg,
                jwsAlgorithm,
                deflatePayload,
                null
        ));
        return ResponseEntity.ok(response);
    }

    /**
     * Generates a JWS-signed ({@code RS256} or {@code Ed25519}) FHIR IPS Document Bundle via POST
     * with custom options or inline clinical summary sections.
     */
    @PostMapping(
            value = {"/api/v1/fhir/Patient/{id}/$summary", "/api/v1/ips/generate"},
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(summary = "Generate a customized JWS-signed FHIR IPS Document Bundle with QR code assets")
    public ResponseEntity<SignedIpsBundleResponse> generateCustomIpsSummary(
            @PathVariable(value = "id", required = false) UUID pathPatientId,
            @RequestHeader(value = "X-Actor-Id", required = false) String actorHeader,
            @RequestHeader(value = "X-Break-The-Glass-Token", required = false) String btgTokenHeader,
            @RequestBody IpsGenerationOptionsRequest request) {

        if (request == null && pathPatientId == null) {
            throw new IllegalArgumentException("IpsGenerationOptionsRequest or path patientId is required");
        }
        UUID resolvedPatientId = pathPatientId != null
                ? pathPatientId
                : (request != null ? request.patientId() : null);
        String resolvedActor = (request != null && request.requestingActorId() != null)
                ? request.requestingActorId()
                : actorHeader;
        String resolvedBtg = (request != null && request.breakTheGlassToken() != null)
                ? request.breakTheGlassToken()
                : btgTokenHeader;

        IpsGenerationOptionsRequest effective = new IpsGenerationOptionsRequest(
                resolvedPatientId,
                resolvedActor,
                resolvedBtg,
                request != null ? request.signingAlgorithm() : "RS256",
                request != null ? request.compressPayloadDeflate() : Boolean.FALSE,
                request != null ? request.inlineClinicalSections() : null
        );
        return ResponseEntity.status(201).body(ipsService.generateSignedPatientSummary(effective));
    }

    /**
     * Seeds or updates a patient's clinical summary data (problems, allergies, medications,
     * immunizations, procedures) for inclusion in subsequent IPS Document Bundles.
     */
    @PostMapping(
            value = "/api/v1/ips/seed",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(summary = "Seed or update clinical problems, allergies, medications, immunizations, and procedures for IPS generation")
    public ResponseEntity<Map<String, Object>> seedPatientIpsData(
            @RequestBody IpsSeedClinicalSummaryRequest request) {
        ipsService.upsertPatientIpsClinicalData(request);
        return ResponseEntity.ok(Map.of(
                "patientId", request.patientId(),
                "status", "IPS_CLINICAL_SUMMARY_UPDATED"
        ));
    }

    /**
     * Retrieves a previously generated JWS-signed IPS Document Bundle by its bundle ID.
     */
    @GetMapping(value = "/api/v1/ips/bundles/{bundleId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get a generated JWS-signed IPS Document Bundle and its QR code payloads by bundleId")
    public ResponseEntity<SignedIpsBundleResponse> getSignedIpsBundle(
            @PathVariable("bundleId") UUID bundleId) {
        return ResponseEntity.ok(ipsService.getSignedIpsBundle(bundleId));
    }

    /**
     * Lists all generated JWS-signed IPS Document Bundles for a patient.
     */
    @GetMapping(value = "/api/v1/ips/patient/{patientId}/history", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List generated JWS-signed IPS Document Bundles for a patient")
    public ResponseEntity<List<SignedIpsBundleResponse>> listPatientIpsBundles(
            @PathVariable("patientId") UUID patientId) {
        return ResponseEntity.ok(ipsService.listPatientIpsBundles(patientId));
    }

    /**
     * Streams the scalable vector SVG QR code image for a generated IPS Document Bundle.
     */
    @GetMapping(value = "/api/v1/ips/bundles/{bundleId}/qr.svg", produces = "image/svg+xml")
    @Operation(summary = "Download the SVG QR code for a JWS-signed IPS Document Bundle")
    public ResponseEntity<String> getIpsBundleQrSvg(@PathVariable("bundleId") UUID bundleId) {
        SignedIpsBundleResponse bundle = ipsService.getSignedIpsBundle(bundleId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("image/svg+xml"))
                .body(bundle.qrCode().qrCodeSvg());
    }

    /**
     * Streams the binary PNG QR code image for a generated IPS Document Bundle.
     */
    @GetMapping(value = "/api/v1/ips/bundles/{bundleId}/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "Download the PNG QR code image for a JWS-signed IPS Document Bundle")
    public ResponseEntity<byte[]> getIpsBundleQrPng(@PathVariable("bundleId") UUID bundleId) {
        SignedIpsBundleResponse bundle = ipsService.getSignedIpsBundle(bundleId);
        String dataUri = bundle.qrCode().qrCodePngBase64DataUri();
        String base64Part = dataUri.startsWith("data:image/png;base64,")
                ? dataUri.substring("data:image/png;base64,".length())
                : dataUri;
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(Base64.getDecoder().decode(base64Part));
    }

    /**
     * Verifies the JWS cryptographic signature ({@code RS256} or {@code Ed25519}/{@code EdDSA}) or
     * scanned mobile QR payload ({@code shc:/...} or {@code hcert:/ips/v1/...}) of an IPS Document Bundle.
     */
    @PostMapping(
            value = "/api/v1/ips/verify",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "Verify the JWS cryptographic signature (RS256 or Ed25519) or QR payload of an IPS Document Bundle",
            description = "Verifies raw JWS compact tokens, SMART Health Card numeric QR URIs (shc:/...), or WHO GDHCN IPS QR payloads (hcert:/ips/v1/...) and returns the decoded FHIR IPS Document Bundle."
    )
    public ResponseEntity<IpsSignatureVerificationResult> verifySignedIpsBundle(
            @RequestBody Map<String, String> payloadMap) {
        if (payloadMap == null) {
            throw new IllegalArgumentException("Verification request payload is required");
        }
        String token = payloadMap.getOrDefault("jwsCompactSignature",
                payloadMap.getOrDefault("jwsOrQrPayload", payloadMap.get("qrPayload")));
        return ResponseEntity.ok(ipsService.verifySignedIpsBundle(token));
    }

    /**
     * Publishes the RFC 7517 JSON Web Key Set (`JWKS`) for IPS `RS256` and `Ed25519` public key verification.
     */
    @GetMapping(value = "/api/v1/ips/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Retrieve RFC 7517 Public JSON Web Key Set (JWKS) for IPS RS256 and Ed25519 verification")
    public ResponseEntity<Map<String, Object>> getPublicIpsJwks() {
        return ResponseEntity.ok(ipsService.getPublicIpsJwks());
    }
}
