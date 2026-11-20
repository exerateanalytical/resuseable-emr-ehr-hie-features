package com.opeshealth.healthcore.biobanking.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@ComponentScan(basePackages = "com.opeshealth.healthcore.biobanking")
@EntityScan(basePackages = "com.opeshealth.healthcore.biobanking.domain")
@EnableJpaRepositories(basePackages = "com.opeshealth.healthcore.biobanking.service", considerNestedRepositories = true)
public class BiobankingAutoConfiguration {
}
