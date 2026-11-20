package com.opeshealth.healthcore.clinicalresearch.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@ComponentScan(basePackages = "com.opeshealth.healthcore.clinicalresearch")
@EntityScan(basePackages = "com.opeshealth.healthcore.clinicalresearch.domain")
@EnableJpaRepositories(basePackages = "com.opeshealth.healthcore.clinicalresearch.service", considerNestedRepositories = true)
public class ClinicalResearchAutoConfiguration {
}
