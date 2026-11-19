/*
 * =============================================================================
 * Opes HealthCore SDK — Automatic Outbox-to-FHIR Subscription Relay Listener
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.hie.service;

import com.opeshealth.healthcore.hie.domain.HieEntities.FhirSubscriptionEntity;
import com.opeshealth.healthcore.hie.service.HieInteropService.FhirSubscriptionRepository;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService.HealthCoreDomainEvent;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Automatically subscribes to domain events emitted by {@code OutboxRelayService}
 * (e.g. {@code PatientRegisteredEvent}, {@code LabResultVerifiedEvent},
 * {@code ColdChainExcursionEvent}, {@code OutbreakThresholdExceededEvent}) and pushes
 * HMAC-signed REST-Hook notifications strictly to matching ACTIVE FHIR Subscriptions
 * belonging to the same {@code facility_id} tenant.
 */
public class FhirOutboxSubscriptionRelay {

    private static final Logger log = LoggerFactory.getLogger(FhirOutboxSubscriptionRelay.class);

    private final FhirSubscriptionRepository subscriptionRepo;
    private final HieInteropService hieService;

    @Value("${healthcore.hie.subscription-relay.enabled:true}")
    private boolean relayEnabled;

    public FhirOutboxSubscriptionRelay(
            FhirSubscriptionRepository subscriptionRepo,
            HieInteropService hieService) {
        this.subscriptionRepo = subscriptionRepo;
        this.hieService = hieService;
    }

    @EventListener
    public void onDomainEvent(HealthCoreDomainEvent event) {
        if (!relayEnabled || event == null || event.facilityId() == null) {
            return;
        }
        UUID eventFacilityId = event.facilityId();
        List<FhirSubscriptionEntity> activeSubs = subscriptionRepo.findByFacilityId(eventFacilityId).stream()
                .filter(s -> "ACTIVE".equalsIgnoreCase(s.getStatus()))
                .toList();
        if (activeSubs.isEmpty()) {
            return;
        }
        String envelopeJson = buildFhirEventEnvelope(event);
        Optional<UUID> previousFacility = FacilityContext.getFacilityId();
        try {
            FacilityContext.setFacilityId(eventFacilityId);
            for (FhirSubscriptionEntity sub : activeSubs) {
                if (matchesSubscription(sub, event)) {
                    try {
                        hieService.dispatchSignedWebhook(sub.getId(), envelopeJson);
                    } catch (Exception ex) {
                        log.warn("FHIR subscription relay {} failed for event {}: {}",
                                sub.getId(), event.eventType(), ex.getMessage());
                    }
                }
            }
        } finally {
            if (previousFacility.isPresent()) {
                FacilityContext.setFacilityId(previousFacility.get());
            } else {
                FacilityContext.clear();
            }
        }
    }

    public boolean matchesSubscription(FhirSubscriptionEntity sub, HealthCoreDomainEvent event) {
        if (sub == null || event == null || sub.getFacilityId() == null || event.facilityId() == null
                || !Objects.equals(sub.getFacilityId(), event.facilityId())) {
            return false;
        }
        String criteria = Objects.toString(sub.getCriteria(), "*").trim().toLowerCase(Locale.ROOT);
        if ("*".equals(criteria) || criteria.isEmpty()) {
            return true;
        }
        String agg = Objects.toString(event.aggregateType(), "").toLowerCase(Locale.ROOT);
        String evt = Objects.toString(event.eventType(), "").toLowerCase(Locale.ROOT);
        return criteria.contains(agg) || criteria.contains(evt);
    }

    public String buildFhirEventEnvelope(HealthCoreDomainEvent event) {
        String safePayload = event.payloadJson() != null ? event.payloadJson() : "{}";
        return "{\"resourceType\":\"Parameters\",\"parameter\":["
                + "{\"name\":\"eventType\",\"valueString\":\"" + escapeJson(event.eventType()) + "\"},"
                + "{\"name\":\"aggregateType\",\"valueString\":\"" + escapeJson(event.aggregateType()) + "\"},"
                + "{\"name\":\"aggregateId\",\"valueString\":\"" + event.aggregateId() + "\"},"
                + "{\"name\":\"payload\",\"valueString\":\"" + escapeJson(safePayload) + "\"}"
                + "]}";
    }

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
