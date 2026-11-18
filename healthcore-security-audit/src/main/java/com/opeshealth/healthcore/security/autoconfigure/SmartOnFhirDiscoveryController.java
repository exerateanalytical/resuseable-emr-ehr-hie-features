/*
 * =============================================================================
 * Opes HealthCore SDK — SMART on FHIR v2 /.well-known/smart-configuration Endpoint
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.security.autoconfigure;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves the HL7 SMART App Launch Framework v2 discovery document at
 * {@code /.well-known/smart-configuration} so external SMART EHR/Patient apps,
 * Apple Health, and KMP mobile clients can discover OAuth2/OIDC endpoints,
 * PKCE S256 support, and granular FHIR scopes dynamically.
 */
@RestController
@Tag(name = "SMART on FHIR v2 Discovery", description = "OAuth2 / OIDC Discovery Metadata for SMART App Launch")
public class SmartOnFhirDiscoveryController {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:http://localhost:8080/realms/opes-healthcore}")
    private String issuerUri;

    @Value("${healthcore.smart.authorization-endpoint:http://localhost:8080/realms/opes-healthcore/protocol/openid-connect/auth}")
    private String authorizationEndpoint;

    @Value("${healthcore.smart.token-endpoint:http://localhost:8080/realms/opes-healthcore/protocol/openid-connect/token}")
    private String tokenEndpoint;

    @Value("${healthcore.smart.introspection-endpoint:http://localhost:8080/realms/opes-healthcore/protocol/openid-connect/token/introspect}")
    private String introspectionEndpoint;

    @Value("${healthcore.smart.revocation-endpoint:http://localhost:8080/realms/opes-healthcore/protocol/openid-connect/revoke}")
    private String revocationEndpoint;

    @GetMapping(value = "/.well-known/smart-configuration", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Return SMART on FHIR v2 /.well-known/smart-configuration capability metadata")
    public Map<String, Object> smartConfiguration() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("issuer", issuerUri);
        doc.put("authorization_endpoint", authorizationEndpoint);
        doc.put("token_endpoint", tokenEndpoint);
        doc.put("introspection_endpoint", introspectionEndpoint);
        doc.put("revocation_endpoint", revocationEndpoint);
        doc.put("grant_types_supported", List.of("authorization_code", "client_credentials", "refresh_token"));
        doc.put("code_challenge_methods_supported", List.of("S256"));
        doc.put("token_endpoint_auth_methods_supported", List.of("client_secret_basic", "client_secret_post", "private_key_jwt"));
        doc.put("scopes_supported", List.of(
                "openid", "fhirUser", "launch", "launch/patient", "offline_access",
                "patient/Patient.rs", "patient/Observation.rs", "patient/MedicationRequest.rs",
                "user/Patient.cruds", "user/Encounter.cruds", "user/DiagnosticReport.cruds",
                "system/*.read"
        ));
        doc.put("capabilities", List.of(
                "launch-ehr",
                "launch-standalone",
                "client-public",
                "client-confidential-symmetric",
                "client-confidential-asymmetric",
                "context-ehr-patient",
                "context-ehr-encounter",
                "context-standalone-patient",
                "permission-offline",
                "permission-patient",
                "permission-user",
                "permission-v2",
                "authorize-post"
        ));
        return doc;
    }
}
