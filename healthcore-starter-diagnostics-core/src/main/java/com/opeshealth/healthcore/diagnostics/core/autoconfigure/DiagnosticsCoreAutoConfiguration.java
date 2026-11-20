package com.opeshealth.healthcore.diagnostics.core.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@ComponentScan(basePackages = "com.opeshealth.healthcore.diagnostics.core")
@EntityScan(basePackages = "com.opeshealth.healthcore.diagnostics.core.domain")
@EnableJpaRepositories(basePackages = "com.opeshealth.healthcore.diagnostics.core.service", considerNestedRepositories = true)
public class DiagnosticsCoreAutoConfiguration {
}
