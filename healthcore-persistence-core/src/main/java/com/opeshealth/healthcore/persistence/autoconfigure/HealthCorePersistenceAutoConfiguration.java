/*
 * =============================================================================
 * Opes HealthCore SDK — Persistence Core Spring Boot 4.0 Auto-Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.persistence.autoconfigure;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.opeshealth.healthcore.persistence.observability.CorrelationAndPhiGuard;
import com.opeshealth.healthcore.persistence.outbox.OutboxClaimStrategy;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.persistence.resilience.ResilienceAndGovernanceSupport.*;
import com.opeshealth.healthcore.persistence.resilience.TwoTierAndKafkaExtensions;
import com.opeshealth.healthcore.persistence.sql.TransientSqlRetryAspect;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;
import java.util.Optional;

/**
 * Auto-configures Opes HealthCore's multi-database persistence core:
 * <ul>
 *   <li>Caffeine L1 (plus optional Redis L2) high-speed cache for terminology, RBAC, and templates</li>
 *   <li>Dialect-aware {@link OutboxClaimStrategy} (PostgreSQL, Windows SQL Server, MySQL)</li>
 *   <li>Default in-process {@link OutboxRelayService} (overridable by Kafka/RabbitMQ)</li>
 *   <li>Deadlock retry aspect, HTTP {@code X-Idempotency-Key} filter, Hibernate tenant filter, PostgreSQL RLS binder, and RFC 7807 / FHIR OperationOutcome advice</li>
 * </ul>
 */
@AutoConfiguration
@EnableCaching
@EnableScheduling
@EntityScan(basePackageClasses = TransactionalOutboxEntity.class)
@EnableJpaRepositories(basePackageClasses = OutboxRelayService.class, considerNestedRepositories = true)
@Import({
        TwoTierAndKafkaExtensions.TwoTierCacheConfiguration.class,
        TwoTierAndKafkaExtensions.KafkaEventPublisherConfiguration.class,
        TwoTierAndKafkaExtensions.RabbitEventPublisherConfiguration.class,
        TwoTierAndKafkaExtensions.PostgresNotifyEventConfiguration.class
})
public class HealthCorePersistenceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CacheManager healthCoreCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(
                "terminologyConcepts", "snomedClosure", "cdsRules",
                "rbacPermissions", "documentTemplates", "facilityProfiles"
        );
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfterWrite(Duration.ofMinutes(30))
                .recordStats());
        return manager;
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxClaimStrategy outboxClaimStrategy(Environment env) {
        String url = env.getProperty("spring.datasource.url", "");
        if (url.contains(":sqlserver:")) {
            return new OutboxClaimStrategy.SqlServerReadPastClaimStrategy();
        }
        return new OutboxClaimStrategy.SkipLockedClaimStrategy();
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxRelayService.EventPublisherSpi eventPublisherSpi(ApplicationEventPublisher publisher) {
        return new OutboxRelayService.SpringInProcessEventPublisher(publisher);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "healthcore.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
    public OutboxRelayService outboxRelayService(
            OutboxClaimStrategy claimStrategy,
            OutboxRelayService.EventPublisherSpi publisherSpi) {
        return new OutboxRelayService(claimStrategy, publisherSpi);
    }

    @Bean
    @ConditionalOnMissingBean
    public TransientSqlRetryAspect transientSqlRetryAspect() {
        return new TransientSqlRetryAspect();
    }

    @Bean
    @ConditionalOnMissingBean
    public CorrelationAndPhiGuard correlationAndPhiGuard() {
        return new CorrelationAndPhiGuard();
    }

    @Bean
    @ConditionalOnMissingBean
    public HttpIdempotencyKeyFilter httpIdempotencyKeyFilter(JdbcTemplate jdbcTemplate) {
        return new HttpIdempotencyKeyFilter(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalProblemDetailAdvice globalProblemDetailAdvice() {
        return new GlobalProblemDetailAdvice();
    }

    @Bean
    @ConditionalOnMissingBean
    public FacilityHibernateFilterAspect facilityHibernateFilterAspect(
            EntityManager entityManager,
            ObjectProvider<ActiveTenantIdSupplier> tenantSupplierProvider) {
        return new FacilityHibernateFilterAspect(
                entityManager,
                tenantSupplierProvider.getIfAvailable(() -> Optional::empty));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "healthcore.postgres.rls", name = "enabled", havingValue = "true")
    public PostgresRlsSessionAspect postgresRlsSessionAspect(
            JdbcTemplate jdbcTemplate,
            ObjectProvider<ActiveTenantIdSupplier> tenantSupplierProvider,
            Environment env) {
        String url = env.getProperty("spring.datasource.url", "");
        boolean isPostgres = url.isEmpty() || url.contains(":postgresql:");
        return new PostgresRlsSessionAspect(
                jdbcTemplate,
                tenantSupplierProvider.getIfAvailable(() -> Optional::empty),
                isPostgres);
    }

    @Bean
    @ConditionalOnMissingBean
    public PostgresAdvisoryLockHelper postgresAdvisoryLockHelper(JdbcTemplate jdbcTemplate, Environment env) {
        String url = env.getProperty("spring.datasource.url", "");
        boolean isPostgres = url.isEmpty() || url.contains(":postgresql:");
        return new PostgresAdvisoryLockHelper(jdbcTemplate, isPostgres);
    }
}
