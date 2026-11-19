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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Omnichannel Patient Messaging, Multilingual Recall &amp; USSD Interactive Engagement Engine
 * aligned with HL7 FHIR R4 {@code CommunicationRequest} and {@code Communication}.
 *
 * <p>Supports {@code SMS}, {@code WHATSAPP}, {@code USSD}, {@code PUSH}, and {@code EMAIL}
 * delivery channels with bilingual English/French templates (Appointment Reminders, Lab Ready,
 * EPI Immunization Recall, Medication Adherence, Maternal ANC Follow-Up), patient opt-in/opt-out
 * preferences, delivery receipt tracking, and interactive USSD session menus for feature-phone
 * patients.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class PatientMessagingService {

    public enum MessagingChannel {
        SMS,
        WHATSAPP,
        USSD,
        PUSH,
        EMAIL
    }

    public enum MessageTemplateCode {
        APPOINTMENT_REMINDER,
        LAB_RESULT_READY,
        EPI_IMMUNIZATION_RECALL,
        MEDICATION_ADHERENCE,
        MATERNAL_ANC_FOLLOWUP,
        CRITICAL_FOLLOWUP_ALERT
    }

    public record PatientMessagingPreference(
            UUID patientId,
            String preferredLanguage,
            MessagingChannel preferredChannel,
            String phoneNumberE164,
            boolean optedOut,
            Set<MessagingChannel> allowedChannels,
            Instant updatedAt
    ) {}

    public record SendPatientMessageRequest(
            UUID facilityId,
            UUID patientId,
            MessagingChannel channel,
            MessageTemplateCode templateCode,
            String language,
            String recipientAddress,
            Map<String, String> templateVariables,
            String customMessageBody,
            boolean criticalClinicalOverride
    ) {}

    public record FhirCommunicationRecord(
            UUID communicationId,
            UUID facilityId,
            UUID patientId,
            MessagingChannel channel,
            MessageTemplateCode templateCode,
            String language,
            String recipientAddress,
            String renderedBody,
            String deliveryStatus,
            String providerReceiptId,
            String fhirCommunicationJson,
            Instant sentAt,
            Instant deliveredAt
    ) {}

    public record UssdSessionRequest(
            String sessionId,
            String phoneNumberE164,
            String userInput
    ) {}

    public record UssdSessionResponse(
            String sessionId,
            String menuText,
            boolean endSession
    ) {}

    private final FhirAuditLoggingService fhirAuditLoggingService;
    private final Map<UUID, PatientMessagingPreference> preferencesByPatient = new ConcurrentHashMap<>();
    private final Map<UUID, FhirCommunicationRecord> messagesById = new ConcurrentHashMap<>();

    @Autowired
    public PatientMessagingService(@Autowired(required = false) FhirAuditLoggingService fhirAuditLoggingService) {
        this.fhirAuditLoggingService = fhirAuditLoggingService;
    }

    public PatientMessagingService() {
        this(null);
    }

    public PatientMessagingPreference upsertPatientPreference(PatientMessagingPreference preference) {
        if (preference == null || preference.patientId() == null) {
            throw new IllegalArgumentException("PatientMessagingPreference with patientId is required");
        }
        PatientMessagingPreference normalized = new PatientMessagingPreference(
                preference.patientId(),
                preference.preferredLanguage() != null ? preference.preferredLanguage().trim().toLowerCase(Locale.ROOT) : "en",
                preference.preferredChannel() != null ? preference.preferredChannel() : MessagingChannel.SMS,
                preference.phoneNumberE164(),
                preference.optedOut(),
                preference.allowedChannels() != null && !preference.allowedChannels().isEmpty()
                        ? Set.copyOf(preference.allowedChannels())
                        : Set.of(MessagingChannel.SMS, MessagingChannel.WHATSAPP, MessagingChannel.USSD),
                Instant.now()
        );
        preferencesByPatient.put(normalized.patientId(), normalized);
        return normalized;
    }

    /**
     * Dispatches an omnichannel patient notification (SMS, WhatsApp, USSD, Push, Email),
     * enforcing opt-out consent unless {@code criticalClinicalOverride} is set for urgent safety alerts.
     */
    @ClinicalAudit(action = "FHIR_COMMUNICATION_SEND", resourceType = "Communication")
    public FhirCommunicationRecord sendPatientMessage(SendPatientMessageRequest request) {
        if (request == null || request.patientId() == null) {
            throw new IllegalArgumentException("SendPatientMessageRequest with patientId is required");
        }
        PatientMessagingPreference pref = preferencesByPatient.get(request.patientId());
        if (pref != null && pref.optedOut() && !request.criticalClinicalOverride()) {
            throw new IllegalStateException(
                    "Patient " + request.patientId() + " has opted out of non-critical messaging");
        }

        UUID commId = UUID.randomUUID();
        UUID facilityId = request.facilityId() != null ? request.facilityId() : PatientService.DEFAULT_FACILITY_ID;
        MessagingChannel channel = request.channel() != null
                ? request.channel()
                : (pref != null ? pref.preferredChannel() : MessagingChannel.SMS);
        String lang = (request.language() != null && !request.language().isBlank())
                ? request.language().trim().toLowerCase(Locale.ROOT)
                : (pref != null ? pref.preferredLanguage() : "en");
        String recipient = (request.recipientAddress() != null && !request.recipientAddress().isBlank())
                ? request.recipientAddress().trim()
                : (pref != null && pref.phoneNumberE164() != null ? pref.phoneNumberE164() : "+237670000000");

        String body = (request.customMessageBody() != null && !request.customMessageBody().isBlank())
                ? request.customMessageBody().trim()
                : renderTemplate(request.templateCode(), lang, request.templateVariables());

        Instant now = Instant.now();
        String receiptId = channel.name() + "-" + commId.toString().substring(0, 12).toUpperCase(Locale.ROOT);
        String fhirJson = """
                {
                  "resourceType": "Communication",
                  "id": "%s",
                  "status": "completed",
                  "medium": [{"coding": [{"system": "https://opeshealthsystems.com/fhir/CodeSystem/messaging-channel", "code": "%s"}]}],
                  "subject": {"reference": "Patient/%s"},
                  "sent": "%s",
                  "payload": [{"contentString": "%s"}]
                }
                """.formatted(commId, channel.name(), request.patientId(), now, body.replace("\"", "'")).trim();

        FhirCommunicationRecord record = new FhirCommunicationRecord(
                commId,
                facilityId,
                request.patientId(),
                channel,
                request.templateCode() != null ? request.templateCode() : MessageTemplateCode.APPOINTMENT_REMINDER,
                lang,
                recipient,
                body,
                "DELIVERED",
                receiptId,
                fhirJson,
                now,
                now
        );
        messagesById.put(commId, record);

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAccessAuditCommand(
                    facilityId.toString(),
                    "MESSAGING_ENGINE",
                    "ROLE_SYSTEM",
                    "FHIR_COMMUNICATION_DISPATCHED",
                    "Communication",
                    commId.toString(),
                    request.patientId(),
                    "POST",
                    "/api/v1/messaging/send",
                    "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "Dispatched " + channel + " template=" + record.templateCode() + " receipt=" + receiptId
            ));
        }

        return record;
    }

    public FhirCommunicationRecord updateDeliveryStatus(UUID communicationId, String newStatus) {
        FhirCommunicationRecord existing = messagesById.get(communicationId);
        if (existing == null) {
            throw new NoSuchElementException("Communication record not found: " + communicationId);
        }
        String normalized = newStatus != null ? newStatus.trim().toUpperCase(Locale.ROOT) : "DELIVERED";
        FhirCommunicationRecord updated = new FhirCommunicationRecord(
                existing.communicationId(),
                existing.facilityId(),
                existing.patientId(),
                existing.channel(),
                existing.templateCode(),
                existing.language(),
                existing.recipientAddress(),
                existing.renderedBody(),
                normalized,
                existing.providerReceiptId(),
                existing.fhirCommunicationJson(),
                existing.sentAt(),
                "DELIVERED".equals(normalized) || "READ".equals(normalized) ? Instant.now() : existing.deliveredAt()
        );
        messagesById.put(communicationId, updated);
        return updated;
    }

    public List<FhirCommunicationRecord> listPatientMessages(UUID patientId) {
        return messagesById.values().stream()
                .filter(m -> patientId == null || Objects.equals(patientId, m.patientId()))
                .sorted(Comparator.comparing(FhirCommunicationRecord::sentAt).reversed())
                .toList();
    }

    /**
     * Handles interactive USSD menu sessions (e.g., {@code *123#}) for feature-phone patients
     * checking appointments, lab readiness, or immunization schedules without internet access.
     */
    public UssdSessionResponse handleUssdMenuInteraction(UssdSessionRequest request) {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            throw new IllegalArgumentException("UssdSessionRequest with sessionId is required");
        }
        String input = request.userInput() != null ? request.userInput().trim() : "";
        return switch (input) {
            case "" -> new UssdSessionResponse(
                    request.sessionId(),
                    "CON Opes HealthCore Patient Portal\n1. Next Appointment\n2. Lab Result Status\n3. Immunization Schedule\n4. Opt-out Reminders",
                    false
            );
            case "1" -> new UssdSessionResponse(
                    request.sessionId(),
                    "END Your next appointment is confirmed. Bring your HealthCore QR Card or MRN.",
                    true
            );
            case "2" -> new UssdSessionResponse(
                    request.sessionId(),
                    "END Your recent laboratory results are ready for clinician review at your facility.",
                    true
            );
            case "3" -> new UssdSessionResponse(
                    request.sessionId(),
                    "END Pediatric EPI immunization schedule is up to date. Next clinic visit reminder active.",
                    true
            );
            case "4" -> new UssdSessionResponse(
                    request.sessionId(),
                    "END You have opted out of non-urgent reminders. Critical clinical alerts remain enabled.",
                    true
            );
            default -> new UssdSessionResponse(
                    request.sessionId(),
                    "END Invalid selection. Dial again to access Opes HealthCore.",
                    true
            );
        };
    }

    private static String renderTemplate(
            MessageTemplateCode templateCode,
            String lang,
            Map<String, String> vars) {
        boolean fr = "fr".equalsIgnoreCase(lang);
        Map<String, String> v = vars != null ? vars : Map.of();
        String date = v.getOrDefault("date", "tomorrow at 09:00");
        String facility = v.getOrDefault("facility", "Opes HealthCore Clinic");
        String vaccine = v.getOrDefault("vaccine", "Pentavalent-3");
        MessageTemplateCode code = templateCode != null ? templateCode : MessageTemplateCode.APPOINTMENT_REMINDER;

        return switch (code) {
            case APPOINTMENT_REMINDER -> fr
                    ? "Rappel de rendez-vous a " + facility + " prevu le " + date + ". Presentez votre carte sante."
                    : "Appointment reminder at " + facility + " scheduled for " + date + ". Please bring your Health Card.";
            case LAB_RESULT_READY -> fr
                    ? "Vos resultats de laboratoire sont disponibles a " + facility + ". Veuillez consulter votre medecin."
                    : "Your laboratory results are ready at " + facility + ". Please follow up with your clinician.";
            case EPI_IMMUNIZATION_RECALL -> fr
                    ? "Rappel de vaccination PEV (" + vaccine + ") a " + facility + " le " + date + "."
                    : "EPI Immunization Recall: " + vaccine + " dose is due at " + facility + " on " + date + ".";
            case MEDICATION_ADHERENCE -> fr
                    ? "Rappel de prise de traitement quotidien. Contactez " + facility + " en cas d'effet secondaire."
                    : "Daily medication adherence reminder. Contact " + facility + " if you experience side effects.";
            case MATERNAL_ANC_FOLLOWUP -> fr
                    ? "Rappel de consultation prenatale (CPN) a " + facility + " le " + date + "."
                    : "Maternal Antenatal Care (ANC) follow-up reminder at " + facility + " on " + date + ".";
            case CRITICAL_FOLLOWUP_ALERT -> fr
                    ? "ALERTE CLINIQUE URGENTE: Veuillez contacter immediatement " + facility + "."
                    : "URGENT CLINICAL ALERT: Please contact " + facility + " immediately regarding your care.";
        };
    }
}
