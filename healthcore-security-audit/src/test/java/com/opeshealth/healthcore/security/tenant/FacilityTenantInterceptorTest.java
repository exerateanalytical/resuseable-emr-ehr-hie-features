package com.opeshealth.healthcore.security.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FacilityTenantInterceptorTest {
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        FacilityContext.clear();
    }

    private static Jwt jwt(UUID facility, List<String> facilities) {
        return Jwt.withTokenValue("synthetic-token")
                .header("alg", "none")
                .claim("sub", "synthetic-user")
                .claim("facility_id", facility.toString())
                .claim("allowed_facility_ids", facilities)
                .build();
    }

    @Test void deniesUnverifiedHeaderAndClearsContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Facility-Id", UUID.randomUUID().toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        new FacilityTenantInterceptor().doFilter(request, response, new MockFilterChain());
        assertEquals(403, response.getStatus());
        assertTrue(FacilityContext.getFacilityId().isEmpty());
    }

    @Test void deniesCrossSiteHeaderUnlessMembershipAndAuthorityBothVerified() throws Exception {
        UUID original = UUID.randomUUID(), foreign = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Facility-Id", foreign.toString());
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt(original, List.of()), List.of(
                        new SimpleGrantedAuthority("PERM_facility:manage"))));
        MockHttpServletResponse response = new MockHttpServletResponse();
        new FacilityTenantInterceptor().doFilter(request, response, new MockFilterChain());
        assertEquals(403, response.getStatus());
        assertTrue(FacilityContext.getFacilityId().isEmpty());

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt(original, List.of(foreign.toString())), List.of(
                        new SimpleGrantedAuthority("PERM_patient:read"))));
        response = new MockHttpServletResponse();
        new FacilityTenantInterceptor().doFilter(request, response, new MockFilterChain());
        assertEquals(403, response.getStatus());
    }

    @Test void allowsVerifiedFacilityAndExplicitAdminMembership() throws Exception {
        UUID original = UUID.randomUUID(), allowed = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt(original, List.of(allowed.toString())), List.of(
                        new SimpleGrantedAuthority("PERM_facility:manage"))));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Facility-Id", allowed.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        new FacilityTenantInterceptor().doFilter(request, response, new MockFilterChain());
        assertEquals(200, response.getStatus());
        assertTrue(FacilityContext.getFacilityId().isEmpty());
    }
}
