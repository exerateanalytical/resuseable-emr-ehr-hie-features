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

import com.opeshealth.healthcore.quickstart.service.PatientMessagingService;
import com.opeshealth.healthcore.quickstart.service.PatientMessagingService.FhirCommunicationRecord;
import com.opeshealth.healthcore.quickstart.service.PatientMessagingService.PatientMessagingPreference;
import com.opeshealth.healthcore.quickstart.service.PatientMessagingService.SendPatientMessageRequest;
import com.opeshealth.healthcore.quickstart.service.PatientMessagingService.UssdSessionRequest;
import com.opeshealth.healthcore.quickstart.service.PatientMessagingService.UssdSessionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * REST Controller for Omnichannel Patient Messaging &amp; Recall (SMS, WhatsApp, USSD, Push, Email).
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/messaging")
@Tag(name = "FHIR R4 Patient Messaging & Recall (SMS / WhatsApp / USSD)",
        description = "Omnichannel FHIR Communication delivery, EN/FR clinical recall templates, opt-out consent controls, and feature-phone USSD sessions")
public class PatientMessagingController {

    private final PatientMessagingService messagingService;

    public PatientMessagingController(PatientMessagingService messagingService) {
        this.messagingService = Objects.requireNonNull(messagingService, "PatientMessagingService is required");
    }

    @PutMapping(value = "/preferences", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Register or update patient messaging channel, language, and opt-out preferences")
    public ResponseEntity<PatientMessagingPreference> upsertPreference(
            @RequestBody PatientMessagingPreference preference) {
        return ResponseEntity.ok(messagingService.upsertPatientPreference(preference));
    }

    @PostMapping(value = "/send", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Send an omnichannel patient notification (SMS, WhatsApp, USSD, Push, Email) mapped to FHIR Communication")
    public ResponseEntity<FhirCommunicationRecord> sendMessage(
            @RequestBody SendPatientMessageRequest request) {
        return ResponseEntity.status(201).body(messagingService.sendPatientMessage(request));
    }

    @PostMapping(value = "/{communicationId}/delivery-receipt", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update delivery status receipt (DELIVERED, READ, FAILED) for a sent Communication")
    public ResponseEntity<FhirCommunicationRecord> updateDeliveryStatus(
            @PathVariable("communicationId") UUID communicationId,
            @RequestParam("status") String status) {
        return ResponseEntity.ok(messagingService.updateDeliveryStatus(communicationId, status));
    }

    @GetMapping(value = "/patient/{patientId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List patient FHIR Communication history")
    public ResponseEntity<List<FhirCommunicationRecord>> listPatientMessages(
            @PathVariable("patientId") UUID patientId) {
        return ResponseEntity.ok(messagingService.listPatientMessages(patientId));
    }

    @PostMapping(value = "/ussd/session", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Handle interactive USSD session menu requests for feature-phone patients")
    public ResponseEntity<UssdSessionResponse> handleUssdSession(
            @RequestBody UssdSessionRequest request) {
        return ResponseEntity.ok(messagingService.handleUssdMenuInteraction(request));
    }
}
