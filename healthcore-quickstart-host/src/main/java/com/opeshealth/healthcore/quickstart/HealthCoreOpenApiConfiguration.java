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

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures OpenAPI 3.1 / Swagger UI with Bearer JWT and SMART on FHIR v2 OAuth2
 * security schemes so integrators can test all EMR, HIE, LIS, PIS, UHC, and DHIS2
 * endpoints interactively.
 */
@Configuration
public class HealthCoreOpenApiConfiguration {

    @Bean
    public OpenAPI healthCoreOpenApi(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:http://localhost:8085/realms/opes-healthcore}") String issuerUri) {
        return new OpenAPI()
                .info(new Info()
                        .title("Opes HealthCore SDK Enterprise API")
                        .version("1.0.0")
                        .description("HL7 FHIR R4, IHE PIXm/PDQm/MHD/mCSD, HL7v2 MLLP/ASTM, DICOMweb, CDS Hooks 2.0, UHC/CBHI & DHIS2 APIs")
                        .contact(new Contact()
                                .name("Jude Nshome — Opes Health Systems")
                                .email("nshome@opesware.com")
                                .url("https://www.opeshealthsystems.com")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT"))
                        .addSecuritySchemes("smartOAuth2", new SecurityScheme()
                                .type(SecurityScheme.Type.OAUTH2)
                                .flows(new OAuthFlows().authorizationCode(new OAuthFlow()
                                        .authorizationUrl(issuerUri + "/protocol/openid-connect/auth")
                                        .tokenUrl(issuerUri + "/protocol/openid-connect/token")
                                        .scopes(new Scopes()
                                                .addString("openid", "OpenID Connect identity")
                                                .addString("fhirUser", "FHIR Practitioner or Patient user")
                                                .addString("patient/*.rs", "SMART v2 patient read/search"))))));
    }
}
