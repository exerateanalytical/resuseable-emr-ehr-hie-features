package com.healthcore.hie;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class HieOutboxRelayWorker {

    private static final Logger log = LoggerFactory.getLogger(HieOutboxRelayWorker.class);

    private final HieOutboxRepository outboxRepository;
    private final RestClient restClient;
    private final boolean relayEnabled;
    private final int maxRetryAttempts;

    public HieOutboxRelayWorker(
            HieOutboxRepository outboxRepository,
            @Value("${healthcore.hie.endpoint-url}") String hieEndpointUrl,
            @Value("${healthcore.hie.relay-enabled:true}") boolean relayEnabled,
            @Value("${healthcore.hie.max-retry-attempts:5}") int maxRetryAttempts
    ) {
        this.outboxRepository = outboxRepository;
        this.restClient = RestClient.builder().baseUrl(hieEndpointUrl).build();
        this.relayEnabled = relayEnabled;
        this.maxRetryAttempts = maxRetryAttempts;
    }

    @Scheduled(fixedDelay = 10000)
    @Transactional
    public void relayPendingBundles() {
        if (!relayEnabled) {
            return;
        }
        List<HieOutboxEventEntity> batch = outboxRepository.claimBatchForRelay(Instant.now());
        for (HieOutboxEventEntity event : batch) {
            try {
                restClient.post()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", event.getIdempotencyKey())
                        .header("X-Facility-Id", event.getFacilityId().toString())
                        .body(event.getPayloadJson())
                        .retrieve()
                        .toBodilessEntity();

                event.setStatus("SENT");
                event.setSentAt(Instant.now());
                event.setLastError(null);
            } catch (Exception ex) {
                int attempts = event.getAttemptCount() + 1;
                event.setAttemptCount(attempts);
                event.setLastError(ex.getMessage());
                if (attempts >= maxRetryAttempts) {
                    event.setStatus("DEAD_LETTER");
                    log.error("HIE outbox event {} moved to DEAD_LETTER after {} attempts", event.getId(), attempts);
                } else {
                    event.setStatus("FAILED");
                    long backoffSeconds = (long) Math.pow(2, attempts) * 15L;
                    event.setNextAttemptAt(Instant.now().plus(Duration.ofSeconds(backoffSeconds)));
                }
            }
        }
    }
}
