package com.opeshealth.healthcore.pharma.serialization.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@ComponentScan(basePackages = "com.opeshealth.healthcore.pharma.serialization")
@EntityScan(basePackages = "com.opeshealth.healthcore.pharma.serialization.domain")
@EnableJpaRepositories(basePackages = "com.opeshealth.healthcore.pharma.serialization.service", considerNestedRepositories = true)
public class PharmaSerializationAutoConfiguration {
}
