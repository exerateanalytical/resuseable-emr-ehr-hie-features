/*
 * =============================================================================
 * Opes HealthCore SDK — Optional Redis L2 Cache & Kafka Outbox Relay Adapters
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.persistence.resilience;

import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.Callable;

/**
 * Provides drop-in distributed scalability extensions when Redis, Kafka, RabbitMQ, or
 * PostgreSQL native {@code LISTEN/NOTIFY} are enabled in a host application:
 * <ul>
 *   <li>{@link TwoTierCacheConfiguration} — L1 Caffeine read-through + L2 Redis cluster sync</li>
 *   <li>{@link KafkaEventPublisherConfiguration} — Kafka-backed {@link OutboxRelayService.EventPublisherSpi}</li>
 *   <li>{@link RabbitEventPublisherConfiguration} — RabbitMQ AMQP {@link OutboxRelayService.EventPublisherSpi}</li>
 *   <li>{@link PostgresNotifyEventConfiguration} — Zero-broker PostgreSQL {@code pg_notify} real-time event fanout</li>
 * </ul>
 */
public final class TwoTierAndKafkaExtensions {

    private TwoTierAndKafkaExtensions() {}

    /** Two-tier L1 (Caffeine) + L2 (Redis) Cache delegating misses from local RAM to Redis. */
    public static class TwoTierCacheManager implements CacheManager {
        private final CacheManager l1;
        private final CacheManager l2;

        public TwoTierCacheManager(CacheManager l1, CacheManager l2) {
            this.l1 = l1;
            this.l2 = l2;
        }

        @Override
        public Cache getCache(String name) {
            Cache c1 = l1.getCache(name);
            Cache c2 = l2.getCache(name);
            if (c1 == null) return c2;
            if (c2 == null) return c1;
            return new Cache() {
                @Override public String getName() { return name; }
                @Override public Object getNativeCache() { return c1.getNativeCache(); }
                @Override public ValueWrapper get(Object key) {
                    ValueWrapper v = c1.get(key);
                    if (v != null) return v;
                    v = c2.get(key);
                    if (v != null) c1.put(key, v.get());
                    return v;
                }
                @Override public <T> T get(Object key, Class<T> type) {
                    T v = c1.get(key, type);
                    if (v != null) return v;
                    v = c2.get(key, type);
                    if (v != null) c1.put(key, v);
                    return v;
                }
                @Override public <T> T get(Object key, Callable<T> valueLoader) {
                    return c1.get(key, () -> {
                        T val = c2.get(key, valueLoader);
                        if (val != null) c2.put(key, val);
                        return val;
                    });
                }
                @Override public void put(Object key, Object value) { c1.put(key, value); c2.put(key, value); }
                @Override public void evict(Object key) { c1.evict(key); c2.evict(key); }
                @Override public void clear() { c1.clear(); c2.clear(); }
            };
        }

        @Override
        public Collection<String> getCacheNames() {
            return l1.getCacheNames();
        }
    }

    @Configuration
    @ConditionalOnClass(RedisConnectionFactory.class)
    @ConditionalOnProperty(prefix = "healthcore.cache.redis", name = "enabled", havingValue = "true")
    public static class TwoTierCacheConfiguration {
        @Bean
        @Primary
        public CacheManager twoTierRedisCacheManager(RedisConnectionFactory connectionFactory) {
            RedisCacheManager l2 = RedisCacheManager.builder(connectionFactory)
                    .cacheDefaults(RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(30)))
                    .build();
            return new TwoTierCacheManager(new ConcurrentMapCacheManager(), l2);
        }
    }

    @Configuration
    @ConditionalOnClass(KafkaTemplate.class)
    @ConditionalOnProperty(prefix = "healthcore.outbox.kafka", name = "enabled", havingValue = "true")
    public static class KafkaEventPublisherConfiguration {
        private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisherConfiguration.class);

        @Bean
        @Primary
        public OutboxRelayService.EventPublisherSpi kafkaOutboxEventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
            return (TransactionalOutboxEntity event) -> {
                String topic = "healthcore." + event.getAggregateType().toLowerCase();
                kafkaTemplate.send(topic, event.getAggregateId().toString(), event.getPayloadJson());
                log.debug("Published outbox event {} to Kafka topic {}", event.getEventType(), topic);
            };
        }
    }

    /**
     * RabbitMQ AMQP outbox relay adapter activated via {@code healthcore.outbox.rabbitmq.enabled=true}.
     */
    @Configuration
    @ConditionalOnClass(RabbitTemplate.class)
    @ConditionalOnProperty(prefix = "healthcore.outbox.rabbitmq", name = "enabled", havingValue = "true")
    public static class RabbitEventPublisherConfiguration {
        private static final Logger log = LoggerFactory.getLogger(RabbitEventPublisherConfiguration.class);

        @Bean
        @Primary
        public OutboxRelayService.EventPublisherSpi rabbitOutboxEventPublisher(RabbitTemplate rabbitTemplate) {
            return (TransactionalOutboxEntity event) -> {
                String routingKey = "healthcore." + event.getAggregateType().toLowerCase() + "." + event.getEventType();
                rabbitTemplate.convertAndSend("healthcore.events", routingKey, event.getPayloadJson());
                log.debug("Published outbox event {} to RabbitMQ exchange healthcore.events ({})",
                        event.getEventType(), routingKey);
            };
        }
    }

    /**
     * Zero-broker PostgreSQL {@code pg_notify} fanout publisher alongside Spring in-process events,
     * enabling multi-pod clusters to receive instant clinical/HIE wakeups directly from PostgreSQL 16+.
     */
    @Configuration
    @ConditionalOnProperty(prefix = "healthcore.postgres.notify", name = "enabled", havingValue = "true")
    public static class PostgresNotifyEventConfiguration {
        private static final Logger log = LoggerFactory.getLogger(PostgresNotifyEventConfiguration.class);

        @Bean
        @Primary
        public OutboxRelayService.EventPublisherSpi postgresNotifyOutboxPublisher(
                JdbcTemplate jdbcTemplate,
                ApplicationEventPublisher springPublisher,
                Environment env) {
            OutboxRelayService.SpringInProcessEventPublisher inProcess =
                    new OutboxRelayService.SpringInProcessEventPublisher(springPublisher);
            String url = env.getProperty("spring.datasource.url", "");
            boolean isPostgres = url.isEmpty() || url.contains(":postgresql:");
            return (TransactionalOutboxEntity event) -> {
                inProcess.publish(event);
                if (isPostgres) {
                    String notifyBody = event.getAggregateType() + ":" + event.getEventType() + ":" + event.getAggregateId();
                    jdbcTemplate.queryForObject("SELECT pg_notify('healthcore_events', ?)", Object.class, notifyBody);
                    log.debug("Dispatched PostgreSQL pg_notify healthcore_events for {}", notifyBody);
                }
            };
        }
    }
}
