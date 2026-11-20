package com.opeshealth.healthcore.genomics.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@ComponentScan(basePackages = "com.opeshealth.healthcore.genomics")
@EntityScan(basePackages = "com.opeshealth.healthcore.genomics.domain")
@EnableJpaRepositories(basePackages = "com.opeshealth.healthcore.genomics.service", considerNestedRepositories = true)
public class GenomicsResearchAutoConfiguration {
}
