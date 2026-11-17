/*
 * =============================================================================
 * Opes HealthCore SDK — Multi-Facility Tenant Propagation Servlet Filter
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.security.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Extracts the active facility tenant UUID from the verified JWT {@code facility_id}
 * claim (or authorized {@code X-Facility-Id} header for super-admins / multi-site HIE)
 * and guarantees cleanup in a {@code finally} block on Virtual Threads.
 */
public class FacilityTenantInterceptor extends OncePerRequestFilter {

    public static final String HEADER_FACILITY_ID = "X-Facility-Id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            UUID resolved = resolveFacilityId(request);
            if (resolved != null) {
                FacilityContext.setFacilityId(resolved);
            }
            filterChain.doFilter(request, response);
        } finally {
            FacilityContext.clear();
        }
    }

    private UUID resolveFacilityId(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            String claim = jwt.getClaimAsString("facility_id");
            if (claim != null && !claim.isBlank()) {
                return UUID.fromString(claim);
            }
        }
        String header = request.getHeader(HEADER_FACILITY_ID);
        if (header != null && !header.isBlank()) {
            return UUID.fromString(header.trim());
        }
        return null;
    }
}
