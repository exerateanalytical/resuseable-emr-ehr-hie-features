/*
 * =============================================================================
 * Opes HealthCore SDK — Server Module Keycloak OIDC / OAuth2 Security Config
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.security;

import com.opeshealth.healthcore.quickstart.security.FhirApiRateLimitFilter.RateLimitPolicy;
import com.opeshealth.healthcore.security.rbac.HealthcareRole;
import com.opeshealth.healthcore.security.tenant.FacilityTenantInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.*;

/**
 * Spring Security configuration in {@code healthcore-quickstart-host} binding Keycloak
 * OIDC/OAuth2 integration directly to environment variables:
 * <ul>
 *   <li>{@code KEYCLOAK_ISSUER_URI} — OIDC issuer validation ({@code iss} claim)</li>
 *   <li>{@code KEYCLOAK_JWK_SET_URI} — Lazy JWKS RSA/EC key verification without blocking boot</li>
 *   <li>{@code KEYCLOAK_CLIENT_ID} — Extracts client roles from {@code resource_access.<clientId>.roles}</li>
 *   <li>{@code KEYCLOAK_ALLOWED_ORIGINS} — Strict CORS origins for web/cockpit clients</li>
 * </ul>
 *
 * <p>Extracts both realm roles ({@code realm_access.roles}) and client roles
 * ({@code resource_access.<clientId>.roles}), maps each {@link HealthcareRole} to
 * granular {@code PERM_*} authorities, preserves SMART on FHIR v2 {@code SCOPE_*} claims,
 * and registers {@link FacilityTenantInterceptor} after Bearer token authentication.</p>
 */
@Configuration
@Profile("keycloak")
@EnableWebSecurity
@EnableMethodSecurity
@ConditionalOnProperty(prefix = "healthcore.security.keycloak", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KeycloakOidcSecurityConfiguration {

    @Value("${healthcore.security.keycloak.issuer-uri:${KEYCLOAK_ISSUER_URI:http://localhost:8085/realms/opes-healthcore}}")
    private String issuerUri;

    @Value("${healthcore.security.keycloak.jwk-set-uri:${KEYCLOAK_JWK_SET_URI:http://localhost:8085/realms/opes-healthcore/protocol/openid-connect/certs}}")
    private String jwkSetUri;

    @Value("${healthcore.security.keycloak.client-id:${KEYCLOAK_CLIENT_ID:healthcore-mobile-kmp}}")
    private String clientId;

    @Value("${healthcore.security.keycloak.allowed-origins:${KEYCLOAK_ALLOWED_ORIGINS:http://localhost:3000,http://localhost:8085}}")
    private String allowedOriginsCsv;

    /**
     * Configures a non-blocking JWKS {@link JwtDecoder} validated against {@code KEYCLOAK_ISSUER_URI}
     * so Spring Boot starts cleanly even if Keycloak initializes in parallel inside Docker Compose.
     */
    @Bean
    @ConditionalOnMissingBean
    public JwtDecoder keycloakJwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri)
        );
        decoder.setJwtValidator(validator);
        return decoder;
    }

    /**
     * Converts Keycloak OIDC JWT claims ({@code realm_access.roles},
     * {@code resource_access.<KEYCLOAK_CLIENT_ID>.roles}, and SMART {@code scope})
     * into Spring Security {@code ROLE_*}, {@code PERM_*}, and {@code SCOPE_*} authorities.
     */
    @Bean(name = "healthCoreJwtAuthenticationConverter")
    public Converter<Jwt, AbstractAuthenticationToken> keycloakJwtAuthenticationConverter() {
        return buildKeycloakJwtConverter(clientId);
    }

    public static JwtAuthenticationConverter buildKeycloakJwtConverter(String keycloakClientId) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName("preferred_username");
        converter.setJwtGrantedAuthoritiesConverter(jwt -> extractKeycloakAuthorities(jwt, keycloakClientId));
        return converter;
    }

    public static Collection<GrantedAuthority> extractKeycloakAuthorities(Jwt jwt, String keycloakClientId) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();

        // 1. Keycloak realm_access.roles
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null && realmAccess.get("roles") instanceof Collection<?> realmRoles) {
            addMappedRoles(realmRoles, authorities);
        }

        // 2. Keycloak resource_access.<clientId>.roles
        Map<String, Object> resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess != null && keycloakClientId != null && !keycloakClientId.isBlank()) {
            Object clientObj = resourceAccess.get(keycloakClientId);
            if (clientObj instanceof Map<?, ?> clientMap && clientMap.get("roles") instanceof Collection<?> clientRoles) {
                addMappedRoles(clientRoles, authorities);
            }
        }

        // 3. Flat permissions claim (optional custom mapper / Auth0 parity)
        Object permissions = jwt.getClaim("permissions");
        if (permissions instanceof Collection<?> perms) {
            perms.forEach(p -> authorities.add(new SimpleGrantedAuthority("PERM_" + p)));
        }

        // 4. OIDC & SMART on FHIR v2 scopes
        String scopeStr = jwt.getClaimAsString("scope");
        if (scopeStr != null && !scopeStr.isBlank()) {
            for (String s : scopeStr.trim().split("\\s+")) {
                authorities.add(new SimpleGrantedAuthority("SCOPE_" + s));
            }
        }
        return authorities;
    }

    private static void addMappedRoles(Collection<?> rawRoles, Set<GrantedAuthority> authorities) {
        for (Object r : rawRoles) {
            if (r == null) continue;
            String roleName = r.toString().trim().toUpperCase(Locale.ROOT);
            if (roleName.isEmpty()) continue;
            authorities.add(new SimpleGrantedAuthority("ROLE_" + roleName));
            try {
                HealthcareRole hr = HealthcareRole.valueOf(roleName);
                hr.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority("PERM_" + p)));
            } catch (IllegalArgumentException ignored) {
                // Retain ROLE_<name> for custom realm/client roles
            }
        }
    }

    @Bean
    public CorsConfigurationSource keycloakCorsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOriginsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        config.setAllowedOrigins(origins.isEmpty() ? List.of("http://localhost:3000") : origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "If-Match", "Idempotency-Key", "X-Facility-Id", "X-Correlation-Id"));
        config.setExposedHeaders(List.of(
                "ETag",
                "X-Correlation-Id",
                "X-RateLimit-Limit",
                "X-RateLimit-Remaining",
                "X-RateLimit-Reset",
                "Retry-After"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    @ConditionalOnMissingBean
    public FhirApiRateLimitFilter fhirApiRateLimitFilter(
            @Value("${healthcore.security.rate-limit.enabled:true}") boolean enabled,
            @Value("${healthcore.security.rate-limit.fhir-requests-per-minute:120}") long fhirRpm,
            @Value("${healthcore.security.rate-limit.bundle-requests-per-minute:30}") long bundleRpm,
            @Value("${healthcore.security.rate-limit.anonymous-requests-per-minute:60}") long anonymousRpm,
            @Value("${healthcore.security.rate-limit.refill-period-seconds:60}") long refillPeriodSeconds,
            @Value("${healthcore.security.rate-limit.max-cached-buckets:10000}") long maxCachedBuckets) {
        return new FhirApiRateLimitFilter(new RateLimitPolicy(
                enabled,
                fhirRpm,
                bundleRpm,
                anonymousRpm,
                Duration.ofSeconds(Math.max(1L, refillPeriodSeconds)),
                maxCachedBuckets
        ));
    }

    @Bean
    public SecurityFilterChain keycloakSecurityFilterChain(
            HttpSecurity http,
            JwtDecoder keycloakJwtDecoder,
            Converter<Jwt, AbstractAuthenticationToken> healthCoreJwtAuthenticationConverter,
            FacilityTenantInterceptor facilityTenantInterceptor,
            FhirApiRateLimitFilter fhirApiRateLimitFilter,
            CorsConfigurationSource keycloakCorsConfigurationSource) throws Exception {
        return http
                .cors(cors -> cors.configurationSource(keycloakCorsConfigurationSource))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health/**",
                                "/actuator/info",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/cds-services",
                                "/api/v1/cds-services",
                                "/.well-known/smart-configuration",
                                "/api/v1/uhc/webhooks/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(keycloakJwtDecoder)
                        .jwtAuthenticationConverter(healthCoreJwtAuthenticationConverter)))
                .addFilterAfter(facilityTenantInterceptor, BearerTokenAuthenticationFilter.class)
                .addFilterAfter(fhirApiRateLimitFilter, FacilityTenantInterceptor.class)
                .build();
    }
}
