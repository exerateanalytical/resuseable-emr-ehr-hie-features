/*
 * =============================================================================
 * Opes HealthCore SDK — Workflow Engine Spring Boot 4.0 Auto-Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.workflow.autoconfigure;

import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.workflow.domain.WorkflowEntities;
import com.opeshealth.healthcore.workflow.service.WorkflowOrchestrationService;
import com.opeshealth.healthcore.workflow.service.WorkflowOrchestrationService.*;
import com.opeshealth.healthcore.workflow.spi.WorkflowSpi.WorkflowSlaEscalationSpi;
import com.opeshealth.healthcore.workflow.spi.WorkflowSpi.WorkflowStepHookSpi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.List;

/**
 * Registers the composable Workflow Engine JPA entities, repositories, orchestration
 * service (auto-wiring any consumer-defined {@link WorkflowStepHookSpi} and
 * {@link WorkflowSlaEscalationSpi} beans), and REST controller.
 */
@AutoConfiguration
@EntityScan(basePackageClasses = WorkflowEntities.class)
@EnableJpaRepositories(basePackageClasses = WorkflowOrchestrationService.class, considerNestedRepositories = true)
public class WorkflowEngineAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public WorkflowOrchestrationService workflowOrchestrationService(
            WorkflowDefinitionRepository definitionRepo,
            WorkflowInstanceRepository instanceRepo,
            WorkflowStepLogRepository stepLogRepo,
            OutboxRelayService.OutboxRepository outboxRepo,
            List<WorkflowStepHookSpi> stepHooks,
            List<WorkflowSlaEscalationSpi> slaHooks) {
        return new WorkflowOrchestrationService(
                definitionRepo, instanceRepo, stepLogRepo, outboxRepo, stepHooks, slaHooks);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowGatewayController workflowGatewayController(WorkflowOrchestrationService service) {
        return new WorkflowGatewayController(service);
    }
}
