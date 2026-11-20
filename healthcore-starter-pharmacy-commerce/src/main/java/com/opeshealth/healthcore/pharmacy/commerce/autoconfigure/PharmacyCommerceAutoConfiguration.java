package com.opeshealth.healthcore.pharmacy.commerce.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@ComponentScan(basePackages = "com.opeshealth.healthcore.pharmacy.commerce")
@EntityScan(basePackages = "com.opeshealth.healthcore.pharmacy.commerce.domain")
@EnableJpaRepositories(basePackages = "com.opeshealth.healthcore.pharmacy.commerce.service", considerNestedRepositories = true)
public class PharmacyCommerceAutoConfiguration {
}
