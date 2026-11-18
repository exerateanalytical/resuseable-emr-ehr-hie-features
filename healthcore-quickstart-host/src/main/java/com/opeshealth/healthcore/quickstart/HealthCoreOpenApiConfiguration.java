/*
 * =============================================================================
 * Opes HealthCore SDK — OpenAPI 3.1 Security & Metadata Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart;

import com.opeshealth.healthcore.quickstart.config.FhirOpenApiConfiguration;
import io.swagger.v3.oas.models.OpenAPI;

/**
 * Legacy helper delegating OpenAPI 3.1 / Swagger UI specification construction to
 * {@link FhirOpenApiConfiguration} so only a single {@link OpenAPI} bean is registered
 * in the Spring application context.
 */
public class HealthCoreOpenApiConfiguration {

    public OpenAPI healthCoreOpenApi(String issuerUri) {
        return new FhirOpenApiConfiguration().healthCoreFhirOpenApi(issuerUri);
    }
}
