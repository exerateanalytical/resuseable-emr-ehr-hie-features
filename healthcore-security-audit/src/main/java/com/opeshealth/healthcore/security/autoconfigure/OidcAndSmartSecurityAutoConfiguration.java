/*
 * =============================================================================
 * Opes HealthCore SDK — OIDC (Keycloak/Auth0) & SMART on FHIR v2 AutoConfig
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.security.autoconfigure;

import com.opeshealth.healthcore.persistence.resilience.ResilienceAndGovernanceSupport.ActiveTenantIdSupplier;
import com.opeshealth.healthcore.security.audit.AuditChainHeadEntity;
import com.opeshealth.healthcore.security.audit.AuditLogEntity;
import com.opeshealth.healthcore.security.audit.AuditRepositories;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import com.opeshealth.healthcore.security.consent.ConsentAndAbacService;
import com.opeshealth.healthcore.security.consent.ConsentPolicyEntity;
import com.opeshealth.healthcore.security.rbac.HealthcareRole;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import com.opeshealth.healthcore.security.tenant.FacilityTenantInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.*;

/**
 * Auto-configures OAuth2 Resource Server security supporting Keycloak realm/resource roles,
 * Auth0 permissions, SMART on FHIR v2 {@code patient/*.rs} / {@code user/*.cruds} scopes,
 * multi-facility tenant context propagation, ABAC consent, and SHA-256 clinical auditing.
 */
@AutoConfiguration
@EnableMethodSecurity
@EnableAspectJAutoProxy
@Import(SmartOnFhirDiscoveryController.class)
@EntityScan(basePackageClasses = {AuditChainHeadEntity.class, AuditLogEntity.class, ConsentPolicyEntity.class})
@EnableJpaRepositories(basePackageClasses = {AuditRepositories.class, ConsentAndAbacService.class})
public class OidcAndSmartSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ClinicalAuditService clinicalAuditService(
            AuditRepositories.AuditChainHeadRepository headRepo,
            AuditRepositories.AuditLogRepository logRepo) {
        return new ClinicalAuditService(headRepo, logRepo);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConsentAndAbacService consentAndAbacService(
            ConsentAndAbacService.ConsentPolicyRepository policyRepo,
            ClinicalAuditService auditService) {
        return new ConsentAndAbacService(policyRepo, auditService);
    }

    @Bean
    @ConditionalOnMissingBean
    public FacilityTenantInterceptor facilityTenantInterceptor() {
        return new FacilityTenantInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean
    public ActiveTenantIdSupplier activeTenantIdSupplier() {
        return FacilityContext::getFacilityId;
    }

    @Bean
    @ConditionalOnMissingBean(name = "healthCoreJwtAuthenticationConverter")
    public Converter<Jwt, AbstractAuthenticationToken> healthCoreJwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Set<GrantedAuthority> authorities = new LinkedHashSet<>();

            // 1. Keycloak realm_access.roles -> ROLE_* + expanded HealthcareRole permissions
            Map<String, Object> realmAccess = jwt.getClaim("realm_access");
            if (realmAccess != null && realmAccess.get("roles") instanceof Collection<?> roles) {
                for (Object r : roles) {
                    String roleName = r.toString().toUpperCase(Locale.ROOT);
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + roleName));
                    try {
                        HealthcareRole hr = HealthcareRole.valueOf(roleName);
                        hr.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority("PERM_" + p)));
                    } catch (IllegalArgumentException ignored) {
                        // Custom role outside base 18 roles still retains ROLE_<name>.
                    }
                }
            }

            // 2. Auth0 flat permissions array & SMART on FHIR v2 scopes
            Object permissions = jwt.getClaim("permissions");
            if (permissions instanceof Collection<?> perms) {
                perms.forEach(p -> authorities.add(new SimpleGrantedAuthority("PERM_" + p)));
            }
            String scopeStr = jwt.getClaimAsString("scope");
            if (scopeStr != null && !scopeStr.isBlank()) {
                for (String s : scopeStr.split("\\s+")) {
                    authorities.add(new SimpleGrantedAuthority("SCOPE_" + s));
                }
            }
            return authorities;
        });
        return converter;
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityFilterChain healthCoreSecurityFilterChain(
            HttpSecurity http,
            Converter<Jwt, AbstractAuthenticationToken> jwtConverter,
            FacilityTenantInterceptor tenantInterceptor) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health/**",
                                "/actuator/info",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/cds-services",
                                "/.well-known/smart-configuration",
                                "/api/v1/uhc/webhooks/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)))
                .addFilterAfter(tenantInterceptor, BearerTokenAuthenticationFilter.class)
                .build();
    }
}
