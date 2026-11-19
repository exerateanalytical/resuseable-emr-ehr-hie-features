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

import com.opeshealth.healthcore.quickstart.service.FederatedHieExchangeService;
import com.opeshealth.healthcore.quickstart.service.FederatedHieExchangeService.FederatedTrustAnchor;
import com.opeshealth.healthcore.quickstart.service.FederatedHieExchangeService.TrustAnchorRegistrationRequest;
import com.opeshealth.healthcore.quickstart.service.FederatedHieExchangeService.UdapClientRegistrationRequest;
import com.opeshealth.healthcore.quickstart.service.FederatedHieExchangeService.UdapRegisteredClient;
import com.opeshealth.healthcore.quickstart.service.FederatedHieExchangeService.XcaDocumentQueryRequest;
import com.opeshealth.healthcore.quickstart.service.FederatedHieExchangeService.XcaQueryResponse;
import com.opeshealth.healthcore.quickstart.service.FederatedHieExchangeService.XcaRetrievedDocument;
import com.opeshealth.healthcore.quickstart.service.FederatedHieExchangeService.XcpdDiscoveryResponse;
import com.opeshealth.healthcore.quickstart.service.FederatedHieExchangeService.XcpdPatientDiscoveryRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * REST Controller for Federated National/Regional HIE Trust &amp; Cross-Community Exchange
 * (UDAP Dynamic Client Registration, TEFCA QHIN Trust Anchors, IHE XCPD, and IHE XCA).
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/hie")
@Tag(name = "Federated HIE Exchange (UDAP / TEFCA / IHE XCPD & XCA)",
        description = "X.509 Trust Anchors, UDAP Dynamic Client Registration, IHE XCPD Cross-Community Patient Discovery, and IHE XCA Cross-Community Query & Retrieve")
public class FederatedHieExchangeController {

    private final FederatedHieExchangeService hieService;

    public FederatedHieExchangeController(FederatedHieExchangeService hieService) {
        this.hieService = Objects.requireNonNull(hieService, "FederatedHieExchangeService is required");
    }

    @GetMapping(value = "/udap/trust-anchors", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List registered regional/national QHIN X.509 Trust Anchors")
    public ResponseEntity<List<FederatedTrustAnchor>> listTrustAnchors() {
        return ResponseEntity.ok(hieService.listTrustAnchors());
    }

    @PostMapping(value = "/udap/trust-anchors", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Register a new TEFCA / National HIE X.509 Trust Anchor")
    public ResponseEntity<FederatedTrustAnchor> registerTrustAnchor(
            @RequestBody TrustAnchorRegistrationRequest request) {
        return ResponseEntity.status(201).body(hieService.registerTrustAnchor(request));
    }

    @PostMapping(value = "/udap/register", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Execute UDAP Dynamic Client Registration validated against a registered X.509 Trust Anchor")
    public ResponseEntity<UdapRegisteredClient> registerUdapClient(
            @RequestBody UdapClientRegistrationRequest request) {
        return ResponseEntity.status(201).body(hieService.registerUdapClient(request));
    }

    @PostMapping(value = "/xcpd/discover", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Execute IHE XCPD Cross-Community Patient Discovery across federated HIE hubs")
    public ResponseEntity<XcpdDiscoveryResponse> discoverPatientAcrossCommunities(
            @RequestBody XcpdPatientDiscoveryRequest request) {
        return ResponseEntity.ok(hieService.discoverPatientAcrossCommunities(request));
    }

    @PostMapping(value = "/xca/query", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Execute IHE XCA Cross-Community Document Query (ITI-38)")
    public ResponseEntity<XcaQueryResponse> queryCrossCommunityDocuments(
            @RequestBody XcaDocumentQueryRequest request) {
        return ResponseEntity.ok(hieService.queryCrossCommunityDocuments(request));
    }

    @GetMapping(value = "/xca/retrieve", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Execute IHE XCA Cross-Community Document Retrieve (ITI-39)")
    public ResponseEntity<XcaRetrievedDocument> retrieveCrossCommunityDocument(
            @RequestParam("homeCommunityId") String homeCommunityId,
            @RequestParam("documentUniqueId") String documentUniqueId) {
        return ResponseEntity.ok(hieService.retrieveCrossCommunityDocument(homeCommunityId, documentUniqueId));
    }
}
