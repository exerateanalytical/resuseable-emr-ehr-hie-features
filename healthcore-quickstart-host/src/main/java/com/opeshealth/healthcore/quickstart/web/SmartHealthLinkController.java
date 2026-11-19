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

import com.opeshealth.healthcore.quickstart.service.SmartHealthLinkService;
import com.opeshealth.healthcore.quickstart.service.SmartHealthLinkService.CreateSmartHealthLinkRequest;
import com.opeshealth.healthcore.quickstart.service.SmartHealthLinkService.DecryptedShlFileResponse;
import com.opeshealth.healthcore.quickstart.service.SmartHealthLinkService.EncryptedShlFile;
import com.opeshealth.healthcore.quickstart.service.SmartHealthLinkService.ShlFileInput;
import com.opeshealth.healthcore.quickstart.service.SmartHealthLinkService.ShlManifestRequest;
import com.opeshealth.healthcore.quickstart.service.SmartHealthLinkService.ShlManifestResponse;
import com.opeshealth.healthcore.quickstart.service.SmartHealthLinkService.SmartHealthLinkResponse;
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

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * REST Controller for HL7 SMART Health Links ({@code shlink:/}) Host, Manifest &amp; Encrypted File Sharing.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/shl")
@Tag(name = "FHIR R4 SMART Health Links (shlink:/)",
        description = "AES-256-GCM encrypted SMART Health Links, passcode-protected manifests, live-updating patient sharing links, revocation, and QR code rendering")
public class SmartHealthLinkController {

    private final SmartHealthLinkService shlService;

    public SmartHealthLinkController(SmartHealthLinkService shlService) {
        this.shlService = Objects.requireNonNull(shlService, "SmartHealthLinkService is required");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create an AES-256-GCM encrypted SMART Health Link (shlink:/) with optional passcode and expiry")
    public ResponseEntity<SmartHealthLinkResponse> createSmartHealthLink(
            @RequestHeader(value = "X-Public-Base-Url", required = false) String baseUrl,
            @RequestBody CreateSmartHealthLinkRequest request) {
        return ResponseEntity.status(201).body(shlService.createSmartHealthLink(request, baseUrl));
    }

    @PostMapping(value = "/{shlId}/files", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Attach an additional encrypted clinical file to an active SMART Health Link")
    public ResponseEntity<EncryptedShlFile> attachEncryptedFile(
            @PathVariable("shlId") UUID shlId,
            @RequestBody ShlFileInput fileInput) {
        return ResponseEntity.status(201).body(shlService.attachEncryptedFile(shlId, fileInput));
    }

    @PostMapping(value = "/{shlId}/manifest", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Resolve a SMART Health Link manifest with passcode, expiry, and max-access enforcement")
    public ResponseEntity<ShlManifestResponse> resolveManifest(
            @PathVariable("shlId") UUID shlId,
            @RequestHeader(value = "X-Public-Base-Url", required = false) String baseUrl,
            @RequestBody ShlManifestRequest request) {
        return ResponseEntity.ok(shlService.resolveManifest(shlId, request, baseUrl));
    }

    @GetMapping(value = "/{shlId}/files/{fileId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Download an AES-256-GCM compact JWE encrypted file from a SMART Health Link")
    public ResponseEntity<EncryptedShlFile> getEncryptedFile(
            @PathVariable("shlId") UUID shlId,
            @PathVariable("fileId") UUID fileId) {
        return ResponseEntity.ok(shlService.getEncryptedFile(shlId, fileId));
    }

    @PostMapping(value = "/{shlId}/files/{fileId}/decrypt", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Verify and decrypt a SMART Health Link encrypted JWE file using the shlink:/ symmetric key")
    public ResponseEntity<DecryptedShlFileResponse> decryptFile(
            @PathVariable("shlId") UUID shlId,
            @PathVariable("fileId") UUID fileId,
            @RequestParam("key") String symmetricKeyBase64Url) {
        return ResponseEntity.ok(shlService.decryptFileWithKey(shlId, fileId, symmetricKeyBase64Url));
    }

    @PostMapping(value = "/{shlId}/revoke", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Revoke a SMART Health Link immediately")
    public ResponseEntity<SmartHealthLinkResponse> revokeSmartHealthLink(
            @PathVariable("shlId") UUID shlId,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestHeader(value = "X-Public-Base-Url", required = false) String baseUrl) {
        return ResponseEntity.ok(shlService.revokeSmartHealthLink(shlId, reason, baseUrl));
    }

    @GetMapping(value = "/patient/{patientId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List SMART Health Links created for a patient")
    public ResponseEntity<List<SmartHealthLinkResponse>> listPatientLinks(
            @PathVariable("patientId") UUID patientId,
            @RequestHeader(value = "X-Public-Base-Url", required = false) String baseUrl) {
        return ResponseEntity.ok(shlService.listPatientSmartHealthLinks(patientId, baseUrl));
    }
}
