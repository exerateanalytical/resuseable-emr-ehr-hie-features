/*
 * =============================================================================
 * Opes HealthCore SDK — API-as-a-Product Auto-Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.apiproduct.autoconfigure;

import com.opeshealth.healthcore.apiproduct.service.ApiProductService;
import com.opeshealth.healthcore.apiproduct.service.ApiProductService.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@EntityScan(basePackageClasses = ApiProductService.class)
@EnableJpaRepositories(basePackageClasses = ApiProductService.class)
public class ApiProductAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ApiProductService apiProductService(DeveloperAppRepository appRepo) {
        return new ApiProductService(appRepo);
    }

    @Bean
    @ConditionalOnMissingBean
    public ApiUsageMeteringFilter apiUsageMeteringFilter(DeveloperAppRepository appRepo) {
        return new ApiUsageMeteringFilter(appRepo);
    }

    @Bean
    @ConditionalOnMissingBean
    public ApiProductController apiProductController(ApiProductService service, DeveloperAppRepository appRepo) {
        return new ApiProductController(service, appRepo);
    }
}
