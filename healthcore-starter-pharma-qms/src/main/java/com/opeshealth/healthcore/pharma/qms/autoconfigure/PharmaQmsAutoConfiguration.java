package com.opeshealth.healthcore.pharma.qms.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@ComponentScan(basePackages = "com.opeshealth.healthcore.pharma.qms")
@EntityScan(basePackages = "com.opeshealth.healthcore.pharma.qms.domain")
@EnableJpaRepositories(basePackages = "com.opeshealth.healthcore.pharma.qms.service", considerNestedRepositories = true)
public class PharmaQmsAutoConfiguration {
}
