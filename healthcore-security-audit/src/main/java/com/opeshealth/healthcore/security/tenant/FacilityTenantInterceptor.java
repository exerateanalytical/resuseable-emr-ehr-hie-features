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
            UUID resolved;
            try {
                resolved = resolveFacilityId(request);
            } catch (IllegalArgumentException ex) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Malformed facility identifier");
                return;
            } catch (org.springframework.security.access.AccessDeniedException ex) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Facility selection is not authorized");
                return;
            }
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
        String rawHeader = request.getHeader(HEADER_FACILITY_ID);
        UUID requested = rawHeader == null || rawHeader.isBlank()
                ? null : UUID.fromString(rawHeader.trim());

        // Never treat a client-provided header as trusted tenant proof.
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Jwt jwt)) {
            if (requested != null) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Facility header requires verified JWT authentication");
            }
            return null;
        }
        String claim = jwt.getClaimAsString("facility_id");
        UUID tokenFacility = claim == null || claim.isBlank() ? null : UUID.fromString(claim);
        if (requested == null) return tokenFacility;
        if (requested.equals(tokenFacility)) return tokenFacility;

        // Cross-site access requires BOTH explicit JWT membership and a privileged role.
        boolean elevated = auth.getAuthorities().stream().anyMatch(a ->
                "PERM_facility:manage".equals(a.getAuthority())
                        || "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
        Object membership = jwt.getClaim("allowed_facility_ids");
        boolean member = membership instanceof java.util.Collection<?> list
                && list.stream().anyMatch(v -> requested.toString().equals(String.valueOf(v)));
        if (!elevated || !member) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Requested facility is not authorized in the verified JWT");
        }
        return requested;
    }
}
