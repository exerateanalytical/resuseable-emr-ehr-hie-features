/*
 * =============================================================================
 * Opes HealthCore SDK — Pluggable Event Bus SPI & Resilient Outbox Relay
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.persistence.outbox;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Event-Driven Architecture (EDA) publisher SPI and resilient outbox relay worker.
 *
 * <p>By default, events publish in-process via Spring's {@link ApplicationEventPublisher}
 * (zero external message broker required for &lt;1-hour clinic setups). Consuming projects
 * can override {@link EventPublisherSpi} to bridge directly into Apache Kafka or RabbitMQ.</p>
 */
public class OutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);
    private static final int MAX_ATTEMPTS = 6;

    @PersistenceContext
    private EntityManager entityManager;

    private final OutboxClaimStrategy claimStrategy;
    private final EventPublisherSpi eventPublisher;

    public OutboxRelayService(OutboxClaimStrategy claimStrategy, EventPublisherSpi eventPublisher) {
        this.claimStrategy = claimStrategy;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Periodically claims a dialect-locked batch and relays domain events with
     * jittered exponential backoff and Dead-Letter Queue (DEAD_LETTER) isolation.
     */
    @Scheduled(fixedDelayString = "${healthcore.outbox.poll-interval-ms:5000}")
    @Transactional
    public void relayPendingEvents() {
        List<TransactionalOutboxEntity> batch = claimStrategy.claimPendingBatch(entityManager, Instant.now(), 50);
        for (TransactionalOutboxEntity event : batch) {
            try {
                eventPublisher.publish(event);
                event.setStatus("SENT");
                event.setSentAt(Instant.now());
                event.setLastError(null);
            } catch (Exception ex) {
                int attempts = event.getAttemptCount() + 1;
                event.setAttemptCount(attempts);
                event.setLastError(ex.getMessage());
                if (attempts >= MAX_ATTEMPTS) {
                    event.setStatus("DEAD_LETTER");
                    log.error("Outbox event {} ({}) moved to DEAD_LETTER after {} attempts",
                            event.getId(), event.getEventType(), attempts);
                } else {
                    event.setStatus("FAILED");
                    // Exponential backoff with +/-20% jitter prevents thundering-herd retries.
                    long baseSeconds = (long) Math.pow(2, attempts) * 10L;
                    long jitter = ThreadLocalRandom.current().nextLong(1, Math.max(2, baseSeconds / 5));
                    event.setNextAttemptAt(Instant.now().plus(Duration.ofSeconds(baseSeconds + jitter)));
                }
            }
        }
    }

    /** SPI allowing external brokers (Kafka/RabbitMQ) to replace the default in-process bus. */
    @FunctionalInterface
    public interface EventPublisherSpi {
        void publish(TransactionalOutboxEntity event);
    }

    /** Zero-infra default publisher emitting typed {@link HealthCoreDomainEvent}s on Spring's bus. */
    public static final class SpringInProcessEventPublisher implements EventPublisherSpi {
        private final ApplicationEventPublisher publisher;

        public SpringInProcessEventPublisher(ApplicationEventPublisher publisher) {
            this.publisher = publisher;
        }

        @Override
        public void publish(TransactionalOutboxEntity event) {
            publisher.publishEvent(new HealthCoreDomainEvent(
                    event.getId(),
                    event.getFacilityId(),
                    event.getAggregateType(),
                    event.getAggregateId(),
                    event.getEventType(),
                    event.getPayloadJson(),
                    event.getCorrelationId()
            ));
        }
    }

    /** Immutable domain event envelope dispatched across Spring / Kafka / RabbitMQ listeners. */
    public record HealthCoreDomainEvent(
            java.util.UUID eventId,
            java.util.UUID facilityId,
            String aggregateType,
            java.util.UUID aggregateId,
            String eventType,
            String payloadJson,
            String correlationId
    ) {}
}
