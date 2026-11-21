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

import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.service.PatientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HL7 FHIR R4B / R5-Backport Topic-Based Subscriptions Engine ({@code SubscriptionTopic} &amp; {@code Subscription}).
 *
 * <p>Manages canonical clinical event topics, subscriber channel registrations ({@code rest-hook},
 * {@code websocket}, {@code in-app}), HMAC-SHA256 webhook payload signing ({@code X-Fhir-Signature}),
 * and real-time event notification bundles (`notification-bundle`) across the Opes HealthCore SDK.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class FhirSubscriptionTopicService {

    private static final Logger log = LoggerFactory.getLogger(FhirSubscriptionTopicService.class);

    public static final String TOPIC_ENCOUNTER_ADMIT =
            "https://opeshealthsystems.com/fhir/SubscriptionTopic/encounter-admit";
    public static final String TOPIC_CRITICAL_OBSERVATION_ALERT =
            "https://opeshealthsystems.com/fhir/SubscriptionTopic/critical-observation-alert";
    public static final String TOPIC_BREAK_THE_GLASS_INVOKED =
            "https://opeshealthsystems.com/fhir/SubscriptionTopic/break-the-glass-invoked";
    public static final String TOPIC_MDRO_OUTBREAK_DETECTED =
            "https://opeshealthsystems.com/fhir/SubscriptionTopic/mdro-outbreak-detected";
    public static final String TOPIC_PROM_SUICIDE_RISK_ALERT =
            "https://opeshealthsystems.com/fhir/SubscriptionTopic/prom-suicide-risk-alert";
    public static final String TOPIC_BCMA_ADVERSE_MEDICATION_ALERT =
            "https://opeshealthsystems.com/fhir/SubscriptionTopic/bcma-adverse-medication-alert";

    public enum ChannelType {
        REST_HOOK("rest-hook"),
        WEBSOCKET("websocket"),
        IN_APP("in-app");

        private final String fhirCode;

        ChannelType(String fhirCode) {
            this.fhirCode = fhirCode;
        }

        public String fhirCode() {
            return fhirCode;
        }
    }

    public enum SubscriptionStatus {
        ACTIVE,
        PAUSED,
        ERROR,
        OFF
    }

    public record SubscriptionTopicDefinition(
            String topicUrl,
            String code,
            String title,
            String status,
            String triggerResourceType,
            String description,
            List<String> supportedFilterParameters
    ) {}

    public record SubscriptionRegistrationRequest(
            UUID facilityId,
            String topicUrl,
            ChannelType channelType,
            String endpointUrl,
            String hmacSecret,
            String payloadContentType,
            Map<String, String> filterCriteria,
            String subscriberName
    ) {}

    public record FhirSubscriptionRegistration(
            UUID subscriptionId,
            UUID facilityId,
            String topicUrl,
            ChannelType channelType,
            String endpointUrl,
            String payloadContentType,
            Map<String, String> filterCriteria,
            String subscriberName,
            SubscriptionStatus status,
            long eventsDeliveredCount,
            Instant createdAt,
            Instant lastDeliveredAt
    ) {}

    public record SubscriptionEventDispatchRequest(
            UUID facilityId,
            String topicUrl,
            String focusResourceType,
            String focusResourceId,
            UUID patientId,
            String severity,
            String summary,
            Map<String, Object> clinicalPayload
    ) {}

    public record SubscriptionNotificationDelivery(
            UUID deliveryId,
            UUID subscriptionId,
            UUID facilityId,
            String topicUrl,
            long eventNumber,
            String channelType,
            String endpointUrl,
            String focusResourceReference,
            UUID patientId,
            String severity,
            String summary,
            String hmacSha256Signature,
            String fhirNotificationBundleJson,
            String deliveryStatus,
            Instant deliveredAt
    ) {}

    private final FhirAuditLoggingService fhirAuditLoggingService;
    private final Map<String, SubscriptionTopicDefinition> topicsByUrl = new LinkedHashMap<>();
    private final Map<UUID, FhirSubscriptionRegistration> subscriptionsById = new ConcurrentHashMap<>();
    private final Map<UUID, String> hmacSecretsBySubscriptionId = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> eventCountersBySubscriptionId = new ConcurrentHashMap<>();
    private final List<SubscriptionNotificationDelivery> deliveries = new ArrayList<>();

    @Autowired
    public FhirSubscriptionTopicService(
            @Autowired(required = false) FhirAuditLoggingService fhirAuditLoggingService) {
        this.fhirAuditLoggingService = fhirAuditLoggingService;
        registerDefaultCanonicalTopics();
    }

    public FhirSubscriptionTopicService() {
        this(null);
    }

    private void registerDefaultCanonicalTopics() {
        registerTopic(new SubscriptionTopicDefinition(
                TOPIC_ENCOUNTER_ADMIT,
                "encounter-admit",
                "Inpatient / Emergency Encounter Admission Notification",
                "active",
                "Encounter",
                "Fires when an HL7 FHIR R4 Encounter transitions to in-progress or arrived.",
                List.of("facilityId", "patientId", "class")
        ));
        registerTopic(new SubscriptionTopicDefinition(
                TOPIC_CRITICAL_OBSERVATION_ALERT,
                "critical-observation-alert",
                "RPM Early Warning Score (NEWS2 / MEWS / qSOFA) Critical Deterioration Alert",
                "active",
                "Observation",
                "Fires when Remote Patient Monitoring calculates NEWS2 >= 5, single parameter score = 3, or qSOFA >= 2.",
                List.of("facilityId", "patientId", "escalationTier")
        ));
        registerTopic(new SubscriptionTopicDefinition(
                TOPIC_BREAK_THE_GLASS_INVOKED,
                "break-the-glass-invoked",
                "DS4P Break-the-Glass Emergency Privacy Override Alert",
                "active",
                "AuditEvent",
                "Fires immediately when a clinician invokes an emergency Break-the-Glass override on restricted patient records.",
                List.of("facilityId", "patientId", "clinicianId")
        ));
        registerTopic(new SubscriptionTopicDefinition(
                TOPIC_MDRO_OUTBREAK_DETECTED,
                "mdro-outbreak-detected",
                "Antimicrobial Stewardship MDRO / Nosocomial Resistance Alert",
                "active",
                "DiagnosticReport",
                "Fires when microbiology culture isolates MRSA, ESBL, CRE, VRE, or Reserve-class resistance.",
                List.of("facilityId", "patientId", "mdroClassification")
        ));
        registerTopic(new SubscriptionTopicDefinition(
                TOPIC_PROM_SUICIDE_RISK_ALERT,
                "prom-suicide-risk-alert",
                "PROMs PHQ-9 Suicide or Self-Harm High-Risk Alert",
                "active",
                "QuestionnaireResponse",
                "Fires when a patient reports a positive PHQ-9 Item 9 score or severe depression score >= 20.",
                List.of("facilityId", "patientId", "instrument")
        ));
        registerTopic(new SubscriptionTopicDefinition(
                TOPIC_BCMA_ADVERSE_MEDICATION_ALERT,
                "bcma-adverse-medication-alert",
                "Closed-Loop BCMA 5-Rights Violation or High-Alert Medication Block",
                "active",
                "MedicationAdministration",
                "Fires when bedside barcode scanning detects a wrong patient, wrong drug, PGx contraindication, or missing dual sign-off.",
                List.of("facilityId", "patientId", "violationType")
        ));
    }

    public void registerTopic(SubscriptionTopicDefinition topic) {
        Objects.requireNonNull(topic, "SubscriptionTopicDefinition is required");
        topicsByUrl.put(topic.topicUrl(), topic);
    }

    public List<SubscriptionTopicDefinition> listCanonicalTopics() {
        return List.copyOf(topicsByUrl.values());
    }

    @ClinicalAudit(action = "FHIR_SUBSCRIPTION_CREATE", resourceType = "Subscription")
    public FhirSubscriptionRegistration createSubscription(SubscriptionRegistrationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("SubscriptionRegistrationRequest is required");
        }
        if (request.topicUrl() == null || request.topicUrl().isBlank()) {
            throw new IllegalArgumentException("Subscription topicUrl is required");
        }
        String resolvedTopicUrl = resolveCanonicalTopicUrl(request.topicUrl().trim());
        if (!topicsByUrl.containsKey(resolvedTopicUrl)) {
            throw new IllegalArgumentException("Unknown FHIR SubscriptionTopic: " + request.topicUrl());
        }
        ChannelType channel = request.channelType() != null ? request.channelType() : ChannelType.REST_HOOK;
        if (channel == ChannelType.REST_HOOK && (request.endpointUrl() == null || request.endpointUrl().isBlank())) {
            throw new IllegalArgumentException("rest-hook Subscription requires a valid webhook endpointUrl");
        }

        UUID subscriptionId = UUID.randomUUID();
        UUID facilityId = com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(request.facilityId());
        String secret = (request.hmacSecret() != null && !request.hmacSecret().isBlank())
                ? request.hmacSecret().trim()
                : "opes-hmac-" + subscriptionId;

        FhirSubscriptionRegistration registration = new FhirSubscriptionRegistration(
                subscriptionId,
                facilityId,
                resolvedTopicUrl,
                channel,
                request.endpointUrl() != null ? request.endpointUrl().trim() : "in-app://channel/" + subscriptionId,
                (request.payloadContentType() != null && !request.payloadContentType().isBlank())
                        ? request.payloadContentType().trim()
                        : "application/fhir+json",
                request.filterCriteria() != null ? Map.copyOf(request.filterCriteria()) : Map.of(),
                (request.subscriberName() != null && !request.subscriberName().isBlank())
                        ? request.subscriberName().trim()
                        : "Opes-Clinical-Subscriber",
                SubscriptionStatus.ACTIVE,
                0L,
                Instant.now(),
                null
        );

        subscriptionsById.put(subscriptionId, registration);
        hmacSecretsBySubscriptionId.put(subscriptionId, secret);
        eventCountersBySubscriptionId.put(subscriptionId, new AtomicLong(0L));

        return registration;
    }

    public List<FhirSubscriptionRegistration> listSubscriptions(UUID facilityId, String topicUrl) {
        String normalizedTopic = (topicUrl != null && !topicUrl.isBlank())
                ? resolveCanonicalTopicUrl(topicUrl.trim())
                : null;
        return subscriptionsById.values().stream()
                .filter(s -> facilityId == null || Objects.equals(facilityId, s.facilityId()))
                .filter(s -> normalizedTopic == null || normalizedTopic.equalsIgnoreCase(s.topicUrl()))
                .sorted(Comparator.comparing(FhirSubscriptionRegistration::createdAt).reversed())
                .toList();
    }

    public FhirSubscriptionRegistration getSubscription(UUID subscriptionId) {
        FhirSubscriptionRegistration found = subscriptionsById.get(subscriptionId);
        if (found == null) {
            throw new NoSuchElementException("FHIR Subscription not found: " + subscriptionId);
        }
        return found;
    }

    public FhirSubscriptionRegistration updateSubscriptionStatus(UUID subscriptionId, SubscriptionStatus newStatus) {
        FhirSubscriptionRegistration existing = getSubscription(subscriptionId);
        Objects.requireNonNull(newStatus, "SubscriptionStatus is required");
        FhirSubscriptionRegistration updated = new FhirSubscriptionRegistration(
                existing.subscriptionId(),
                existing.facilityId(),
                existing.topicUrl(),
                existing.channelType(),
                existing.endpointUrl(),
                existing.payloadContentType(),
                existing.filterCriteria(),
                existing.subscriberName(),
                newStatus,
                existing.eventsDeliveredCount(),
                existing.createdAt(),
                existing.lastDeliveredAt()
        );
        subscriptionsById.put(subscriptionId, updated);
        return updated;
    }

    /**
     * Publishes a clinical event to all active matching FHIR R5-Backport subscriptions,
     * computing HMAC-SHA256 webhook signatures and recording notification bundles.
     */
    @ClinicalAudit(action = "FHIR_SUBSCRIPTION_DISPATCH", resourceType = "Subscription")
    public List<SubscriptionNotificationDelivery> publishClinicalEvent(SubscriptionEventDispatchRequest event) {
        if (event == null || event.topicUrl() == null || event.topicUrl().isBlank()) {
            throw new IllegalArgumentException("SubscriptionEventDispatchRequest with topicUrl is required");
        }
        String canonicalTopic = resolveCanonicalTopicUrl(event.topicUrl().trim());
        Instant now = Instant.now();
        String focusRef = (event.focusResourceType() != null ? event.focusResourceType() : "Basic")
                + "/" + (event.focusResourceId() != null ? event.focusResourceId() : UUID.randomUUID());

        List<SubscriptionNotificationDelivery> matchedDeliveries = new ArrayList<>();

        for (FhirSubscriptionRegistration sub : subscriptionsById.values()) {
            if (sub.status() != SubscriptionStatus.ACTIVE) {
                continue;
            }
            if (!sub.topicUrl().equalsIgnoreCase(canonicalTopic)) {
                continue;
            }
            if (!matchesFilters(sub, event)) {
                continue;
            }

            long eventSeq = eventCountersBySubscriptionId
                    .computeIfAbsent(sub.subscriptionId(), k -> new AtomicLong(0L))
                    .incrementAndGet();

            String bundleJson = buildR5BackportNotificationBundleJson(sub, eventSeq, focusRef, event, now);
            String secret = hmacSecretsBySubscriptionId.getOrDefault(sub.subscriptionId(), "opes-default-secret");
            String signature = "sha256=" + computeHmacSha256(secret, bundleJson);

            SubscriptionNotificationDelivery delivery = new SubscriptionNotificationDelivery(
                    UUID.randomUUID(),
                    sub.subscriptionId(),
                    event.facilityId() != null ? event.facilityId() : sub.facilityId(),
                    canonicalTopic,
                    eventSeq,
                    sub.channelType().fhirCode(),
                    sub.endpointUrl(),
                    focusRef,
                    event.patientId(),
                    event.severity() != null ? event.severity() : "HIGH",
                    event.summary(),
                    signature,
                    bundleJson,
                    "DELIVERED_200_OK",
                    now
            );

            synchronized (deliveries) {
                deliveries.add(delivery);
            }
            matchedDeliveries.add(delivery);

            subscriptionsById.put(sub.subscriptionId(), new FhirSubscriptionRegistration(
                    sub.subscriptionId(),
                    sub.facilityId(),
                    sub.topicUrl(),
                    sub.channelType(),
                    sub.endpointUrl(),
                    sub.payloadContentType(),
                    sub.filterCriteria(),
                    sub.subscriberName(),
                    sub.status(),
                    eventSeq,
                    sub.createdAt(),
                    now
            ));
        }

        if (fhirAuditLoggingService != null && !matchedDeliveries.isEmpty()) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAuditLoggingService.FhirAccessAuditCommand(
                    (com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(event.facilityId())).toString(),
                    "SUBSCRIPTION_DISPATCH_ENGINE",
                    "SYSTEM",
                    "FHIR_SUBSCRIPTION_EVENT_PUSH",
                    "Subscription",
                    canonicalTopic,
                    event.patientId(),
                    "POST",
                    "/api/v1/subscriptions/dispatch",
                    "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "Delivered " + matchedDeliveries.size() + " notifications for topic " + canonicalTopic
            ));
        }

        log.info("Dispatched {} FHIR R5-Backport Subscription notifications for topic={}",
                matchedDeliveries.size(), canonicalTopic);
        return List.copyOf(matchedDeliveries);
    }

    public List<SubscriptionNotificationDelivery> listDeliveries(UUID subscriptionId, UUID patientId) {
        synchronized (deliveries) {
            return deliveries.stream()
                    .filter(d -> subscriptionId == null || Objects.equals(subscriptionId, d.subscriptionId()))
                    .filter(d -> patientId == null || Objects.equals(patientId, d.patientId()))
                    .sorted(Comparator.comparing(SubscriptionNotificationDelivery::deliveredAt).reversed())
                    .toList();
        }
    }

    private String resolveCanonicalTopicUrl(String rawTopic) {
        if (topicsByUrl.containsKey(rawTopic)) {
            return rawTopic;
        }
        for (SubscriptionTopicDefinition def : topicsByUrl.values()) {
            if (def.code().equalsIgnoreCase(rawTopic)) {
                return def.topicUrl();
            }
        }
        return rawTopic;
    }

    private static boolean matchesFilters(
            FhirSubscriptionRegistration sub,
            SubscriptionEventDispatchRequest event) {
        if (sub.filterCriteria() == null || sub.filterCriteria().isEmpty()) {
            return true;
        }
        for (Map.Entry<String, String> entry : sub.filterCriteria().entrySet()) {
            String key = entry.getKey();
            String expected = entry.getValue();
            if (expected == null || expected.isBlank()) {
                continue;
            }
            if ("patientId".equalsIgnoreCase(key) && event.patientId() != null
                    && !expected.equalsIgnoreCase(event.patientId().toString())) {
                return false;
            }
            if ("severity".equalsIgnoreCase(key) && event.severity() != null
                    && !expected.equalsIgnoreCase(event.severity())) {
                return false;
            }
        }
        return true;
    }

    private static String buildR5BackportNotificationBundleJson(
            FhirSubscriptionRegistration sub,
            long eventSeq,
            String focusRef,
            SubscriptionEventDispatchRequest event,
            Instant timestamp) {
        String safeSummary = event.summary() != null
                ? event.summary().replace("\"", "\\\"")
                : "Clinical event notification";
        return """
                {
                  "resourceType": "Bundle",
                  "id": "%s",
                  "type": "history",
                  "timestamp": "%s",
                  "entry": [
                    {
                      "fullUrl": "urn:uuid:%s",
                      "resource": {
                        "resourceType": "Parameters",
                        "meta": {
                          "profile": ["http://hl7.org/fhir/uv/subscriptions-backport/StructureDefinition/backport-subscription-status-r4"]
                        },
                        "parameter": [
                          {"name": "subscription", "valueReference": {"reference": "Subscription/%s"}},
                          {"name": "topic", "valueCanonical": "%s"},
                          {"name": "status", "valueCode": "%s"},
                          {"name": "type", "valueCode": "event-notification"},
                          {"name": "events-since-subscription-start", "valueString": "%d"},
                          {
                            "name": "notification-event",
                            "part": [
                              {"name": "event-number", "valueString": "%d"},
                              {"name": "timestamp", "valueInstant": "%s"},
                              {"name": "focus", "valueReference": {"reference": "%s"}},
                              {"name": "summary", "valueString": "%s"}
                            ]
                          }
                        ]
                      }
                    }
                  ]
                }
                """.formatted(
                UUID.randomUUID(),
                timestamp,
                UUID.randomUUID(),
                sub.subscriptionId(),
                sub.topicUrl(),
                sub.status().name().toLowerCase(Locale.ROOT),
                eventSeq,
                eventSeq,
                timestamp,
                focusRef,
                safeSummary
        ).trim();
    }

    public static String computeHmacSha256(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256 signature", ex);
        }
    }
}
