/*
 * =============================================================================
 * Opes HealthCore SDK — HIE/FHIR/IHE Strict Security & Tenant Enforcement Config
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.hie.security;

import com.opeshealth.healthcore.security.tenant.FacilityContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Dedicated least-privilege Spring Security configuration for {@code healthcore-starter-hie-interop}.
 *
 * <p>Enforces two mandatory defense-in-depth invariants across all FHIR R4, IHE, HL7v2, ASTM,
 * DICOM, and CDA endpoints ({@code /fhir/**}, {@code /api/v1/hie/**}, {@code /api/v1/ihe/**}):</p>
 * <ol>
 *   <li><strong>Zero {@code isAuthenticated()} fallbacks:</strong> Every FHIR and IHE endpoint
 *       requires explicit least-privilege authorities — strictly requiring
 *       {@code hasAuthority('PERM_patient:read')} on patient, document, imaging, and directory
 *       reads — with {@code anyRequest().denyAll()} as the chain default.</li>
 *   <li><strong>Strict organization / {@code facility_id} tenant isolation:</strong>
 *       {@link HieTenantEnforcementFilter} verifies the authenticated JWT's assigned
 *       {@code facility_id} claim and rejects any cross-organization {@code X-Facility-Id} header
 *       or {@code facilityId} query parameter mismatch with HTTP {@code 403 Forbidden} (FHIR
 *       {@code OperationOutcome}) before controller execution.</li>
 * </ol>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Configuration
@EnableMethodSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class HieInteropSecurityConfiguration {

    public static final String HEADER_FACILITY_ID = "X-Facility-Id";
    private static final List<String> JWT_FACILITY_CLAIM_KEYS = List.of("facility_id", "organization_id", "tenant_id");

    @Bean
    @ConditionalOnMissingBean
    public HieTenantEnforcementFilter hieTenantEnforcementFilter() {
        return new HieTenantEnforcementFilter();
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    @ConditionalOnMissingBean(name = "hieInteropSecurityFilterChain")
    public SecurityFilterChain hieInteropSecurityFilterChain(
            HttpSecurity http,
            ObjectProvider<Converter<Jwt, AbstractAuthenticationToken>> jwtConverterProvider,
            HieTenantEnforcementFilter tenantEnforcementFilter) throws Exception {

        Converter<Jwt, AbstractAuthenticationToken> jwtConverter = jwtConverterProvider.getIfAvailable();

        return http
                .securityMatcher("/fhir/**", "/api/v1/hie/**", "/api/v1/ihe/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Bulk FHIR export requires both patient read and export/admin authority
                        .requestMatchers(HttpMethod.GET, "/fhir/r4/Patient/$export")
                            .access((authentication, context) -> {
                                Authentication a = authentication.get();
                                boolean ok = hasAuthority(a, "PERM_patient:read")
                                        && (hasAuthority(a, "PERM_audit:export") || hasAuthority(a, "PERM_system:admin"));
                                return new org.springframework.security.authorization.AuthorizationDecision(ok);
                            })
                        // FHIR & IHE read endpoints strictly enforce PERM_patient:read (zero isAuthenticated() fallbacks)
                        .requestMatchers(HttpMethod.GET,
                                "/fhir/r4/metadata",
                                "/fhir/r4/Patient",
                                "/fhir/r4/Patient/**",
                                "/fhir/r4/DocumentReference",
                                "/fhir/r4/DocumentReference/**",
                                "/fhir/r4/Organization",
                                "/fhir/r4/Organization/**",
                                "/fhir/r4/Location",
                                "/fhir/r4/Location/**",
                                "/api/v1/ihe/**",
                                "/api/v1/hie/cda/**",
                                "/api/v1/hie/dicom/studies",
                                "/api/v1/hie/dicom/pacs/studies/**",
                                "/api/v1/hie/dicom/pacs/qido"
                        ).hasAuthority("PERM_patient:read")
                        // FHIR transaction/batch and IHE MHD writes require PERM_patient:read plus clinical write permission
                        .requestMatchers(HttpMethod.POST,
                                "/fhir/r4",
                                "/fhir/r4/DocumentReference",
                                "/api/v1/ihe/mhd/**"
                        ).access((authentication, context) -> {
                            Authentication a = authentication.get();
                            boolean ok = hasAuthority(a, "PERM_patient:read")
                                    && (hasAuthority(a, "PERM_patient:write") || hasAuthority(a, "PERM_encounter:sign"));
                            return new org.springframework.security.authorization.AuthorizationDecision(ok);
                        })
                        // FHIR Subscription administration
                        .requestMatchers(HttpMethod.POST, "/fhir/r4/Subscription", "/fhir/r4/Subscription/**")
                            .hasAnyAuthority("PERM_system:admin", "PERM_facility:manage")
                        // HL7v2, ASTM & DICOM ingestion endpoints
                        .requestMatchers(HttpMethod.POST, "/api/v1/hie/hl7v2/**")
                            .hasAnyAuthority("PERM_patient:write", "PERM_lab:result:enter")
                        .requestMatchers(HttpMethod.POST, "/api/v1/hie/astm/**")
                            .hasAnyAuthority("PERM_lab:result:enter", "PERM_lab:verify")
                        .requestMatchers(HttpMethod.POST, "/api/v1/hie/dicom/**")
                            .hasAnyAuthority("PERM_dicom:index", "PERM_imaging:report:sign", "PERM_lab:verify")
                        // Fail closed for any unrecognized HIE/FHIR/IHE route (never fall back to isAuthenticated())
                        .anyRequest().denyAll()
                )
                .oauth2ResourceServer(oauth2 -> {
                    if (jwtConverter != null) {
                        oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter));
                    } else {
                        oauth2.jwt(jwt -> {});
                    }
                })
                .addFilterAfter(tenantEnforcementFilter, BearerTokenAuthenticationFilter.class)
                .build();
    }

    /**
     * Extracts the caller's assigned facility UUID from the authenticated JWT principal, if present.
     */
    public static Optional<UUID> extractAssignedFacilityId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        for (String claimKey : JWT_FACILITY_CLAIM_KEYS) {
            String raw = jwt.getClaimAsString(claimKey);
            if (raw != null && !raw.isBlank()) {
                return Optional.of(parseStrictUuid(raw.trim(), "JWT claim '" + claimKey + "'"));
            }
        }
        return Optional.empty();
    }

    public static boolean hasAuthority(Authentication authentication, String authority) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getAuthorities() == null) {
            return false;
        }
        for (GrantedAuthority ga : authentication.getAuthorities()) {
            if (authority.equals(ga.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private static UUID parseStrictUuid(String value, String sourceName) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid UUID format in " + sourceName + ": " + value, ex);
        }
    }

    /**
     * Servlet filter enforcing strict organization ({@code facility_id}) tenant binding on every
     * FHIR, IHE, and HIE HTTP request.
     *
     * <ul>
     *   <li>If the verified JWT contains an assigned {@code facility_id} (or {@code organization_id} /
     *       {@code tenant_id}), any conflicting {@code X-Facility-Id} header or {@code facilityId}
     *       request parameter is rejected with HTTP {@code 403 Forbidden}.</li>
     *   <li>If the JWT has no assigned {@code facility_id}, the request is rejected with HTTP
     *       {@code 403 Forbidden} unless the caller holds {@code PERM_system:admin} and supplies a
     *       valid {@code X-Facility-Id} header.</li>
     * </ul>
     */
    public static class HieTenantEnforcementFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            try {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth == null || !auth.isAuthenticated()) {
                    writeForbiddenOutcome(response, "Authentication is required for FHIR/IHE tenant access.");
                    return;
                }

                UUID resolvedTenant;
                try {
                    resolvedTenant = resolveAndValidateTenant(request, auth);
                } catch (IllegalArgumentException ex) {
                    writeForbiddenOutcome(response, ex.getMessage());
                    return;
                }

                if (resolvedTenant == null) {
                    writeForbiddenOutcome(response,
                            "Assigned organization facility_id claim is required; cross-tenant HIE access is prohibited.");
                    return;
                }

                FacilityContext.setFacilityId(resolvedTenant);
                filterChain.doFilter(request, response);
            } finally {
                FacilityContext.clear();
            }
        }

        UUID resolveAndValidateTenant(HttpServletRequest request, Authentication auth) {
            Optional<UUID> jwtFacility = extractAssignedFacilityId(auth);
            String headerRaw = request.getHeader(HEADER_FACILITY_ID);
            String queryRaw = request.getParameter("facilityId");

            UUID headerFacility = (headerRaw != null && !headerRaw.isBlank())
                    ? parseStrictUuid(headerRaw.trim(), HEADER_FACILITY_ID + " header")
                    : null;
            UUID queryFacility = (queryRaw != null && !queryRaw.isBlank())
                    ? parseStrictUuid(queryRaw.trim(), "facilityId query parameter")
                    : null;

            if (jwtFacility.isPresent()) {
                UUID assigned = jwtFacility.get();
                if (headerFacility != null && !assigned.equals(headerFacility)) {
                    throw new IllegalArgumentException(
                            "Cross-organization access denied: X-Facility-Id header does not match authenticated user's assigned facility_id.");
                }
                if (queryFacility != null && !assigned.equals(queryFacility)) {
                    throw new IllegalArgumentException(
                            "Cross-organization access denied: facilityId parameter does not match authenticated user's assigned facility_id.");
                }
                return assigned;
            }

            // No facility_id claim in token: ONLY system administrators may explicitly scope a request via X-Facility-Id
            if (hasAuthority(auth, "PERM_system:admin") && headerFacility != null) {
                if (queryFacility != null && !headerFacility.equals(queryFacility)) {
                    throw new IllegalArgumentException(
                            "Cross-organization access denied: facilityId parameter does not match X-Facility-Id header.");
                }
                return headerFacility;
            }
            return null;
        }

        private static void writeForbiddenOutcome(HttpServletResponse response, String diagnostics) throws IOException {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/fhir+json");
            String escaped = diagnostics == null ? "Forbidden" : diagnostics.replace("\\", "\\\\").replace("\"", "\\\"");
            response.getWriter().write(
                    "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"forbidden\",\"diagnostics\":\""
                            + escaped + "\"}]}");
        }
    }
}
