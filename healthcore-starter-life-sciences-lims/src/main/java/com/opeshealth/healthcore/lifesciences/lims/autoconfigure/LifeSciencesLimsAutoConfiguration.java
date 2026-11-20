package com.opeshealth.healthcore.lifesciences.lims.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@ComponentScan(basePackages = "com.opeshealth.healthcore.lifesciences.lims")
@EntityScan(basePackages = "com.opeshealth.healthcore.lifesciences.lims.domain")
@EnableJpaRepositories(basePackages = "com.opeshealth.healthcore.lifesciences.lims.service", considerNestedRepositories = true)
public class LifeSciencesLimsAutoConfiguration {
}
