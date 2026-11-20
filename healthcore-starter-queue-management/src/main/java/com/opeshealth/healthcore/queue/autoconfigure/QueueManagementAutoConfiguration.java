/*
 * =============================================================================
 * Opes HealthCore SDK — Departmental Queue Management Auto-Configuration
 * =============================================================================
 */
package com.opeshealth.healthcore.queue.autoconfigure;

import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.queue.domain.QueueManagementEntities;
import com.opeshealth.healthcore.queue.service.QueueManagementService;
import com.opeshealth.healthcore.queue.service.QueueManagementService.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.List;

@AutoConfiguration
@EntityScan(basePackageClasses = QueueManagementEntities.class)
@EnableJpaRepositories(basePackageClasses = QueueManagementService.class, considerNestedRepositories = true)
public class QueueManagementAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public QueueManagementService queueManagementService(
            QueueCounterRepository counterRepo,
            DepartmentQueueEntryRepository entryRepo,
            QueuePauseControlRepository pauseRepo,
            ObjectProvider<OutboxRelayService.OutboxRepository> outboxRepoProvider,
            ObjectProvider<QueuePrioritizationSpi> prioritizationSpiProvider,
            ObjectProvider<QueueEventSubscriptionSpi> subscribersProvider) {
        return new QueueManagementService(
                counterRepo,
                entryRepo,
                pauseRepo,
                outboxRepoProvider.getIfAvailable(),
                prioritizationSpiProvider.getIfAvailable(),
                subscribersProvider.orderedStream().toList()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public QueueManagementController queueManagementController(QueueManagementService service) {
        return new QueueManagementController(service);
    }
}
