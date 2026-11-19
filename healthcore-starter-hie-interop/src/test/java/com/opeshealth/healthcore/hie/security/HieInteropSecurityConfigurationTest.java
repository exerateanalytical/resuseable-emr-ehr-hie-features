/*
 * =============================================================================
 * Opes HealthCore SDK — HIE/FHIR/IHE Security & Tenant Enforcement Unit Tests
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.hie.security;

import com.opeshealth.healthcore.hie.security.HieInteropSecurityConfiguration.HieTenantEnforcementFilter;
import com.opeshealth.healthcore.hie.service.DicomPacsProxyService;
import com.opeshealth.healthcore.hie.service.HieInteropService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@code healthcore-starter-hie-interop} enforces:
 * <ul>
 *   <li>Zero {@code isAuthenticated()} fallbacks on all FHIR, IHE, HL7, DICOM, and CDA endpoints</li>
 *   <li>Strict {@code hasAuthority('PERM_patient:read')} on FHIR and IHE endpoints</li>
 *   <li>Strict organization {@code facility_id} tenant isolation blocking cross-tenant spoofing</li>
 * </ul>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
class HieInteropSecurityConfigurationTest {

    @AfterEach
    void cleanup() {
        FacilityContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void allFhirAndIheEndpointsEnforceStrictPermissionsWithoutIsAuthenticatedFallback() {
        List<Class<?>> fhirAndIheControllers = List.of(
                HieInteropService.FhirHieController.class,
                HieInteropService.InteroperabilityIheController.class,
                HieInteropService.Hl7AstmDicomController.class,
                DicomPacsProxyService.DicomPacsController.class
        );

        for (Class<?> controller : fhirAndIheControllers) {
            for (Method method : controller.getDeclaredMethods()) {
                if (method.isSynthetic() || !java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
                assertNotNull(preAuthorize,
                        "Every public HIE/FHIR/IHE endpoint must declare @PreAuthorize: "
                                + controller.getSimpleName() + "." + method.getName());
                String expr = preAuthorize.value();
                assertFalse(expr.contains("isAuthenticated()"),
                        "isAuthenticated() fallback is prohibited on " + controller.getSimpleName() + "." + method.getName());
            }
        }

        // Verify strict PERM_patient:read across all FHIR/IHE read endpoints
        List<String> expectedReadMethods = List.of(
                "capabilityStatement", "ipsSummary", "bulkExport",
                "ihePix", "ihePdqm", "findDocuments", "mcsdOrganizations", "mcsdLocations",
                "queryDicom", "cdaXml", "wadoStudy", "wadoInstance", "qidoQuery"
        );
        for (Class<?> controller : fhirAndIheControllers) {
            for (Method method : controller.getDeclaredMethods()) {
                if (expectedReadMethods.contains(method.getName())) {
                    PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
                    assertNotNull(preAuthorize);
                    assertTrue(preAuthorize.value().contains("hasAuthority('PERM_patient:read')"),
                            "Endpoint must strictly require PERM_patient:read: "
                                    + controller.getSimpleName() + "." + method.getName());
                }
            }
        }
    }

    @Test
    void filterRejectsCrossOrganizationHeaderSpoofingWithHttp403() throws Exception {
        UUID assignedOrg = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();

        Jwt jwt = buildJwt(assignedOrg.toString());
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("PERM_patient:read"))));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/fhir/r4/Patient");
        request.addHeader(HieInteropSecurityConfiguration.HEADER_FACILITY_ID, otherOrg.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();

        HieTenantEnforcementFilter filter = new HieTenantEnforcementFilter();
        AtomicReference<Boolean> chainInvoked = new AtomicReference<>(false);
        FilterChain chain = (req, res) -> chainInvoked.set(true);

        filter.doFilter(request, response, chain);

        assertFalse(chainInvoked.get(), "Filter must block cross-organization header spoofing");
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("OperationOutcome"));
        assertTrue(FacilityContext.getFacilityId().isEmpty(), "FacilityContext must remain clean after rejection");
    }

    @Test
    void filterRejectsUnassignedUserAttemptingToPickArbitraryFacilityHeader() throws Exception {
        UUID targetOrg = UUID.randomUUID();

        Jwt jwtWithoutFacility = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject("user-without-org")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwtWithoutFacility, List.of(new SimpleGrantedAuthority("PERM_patient:read"))));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/fhir/r4/Patient");
        request.addHeader(HieInteropSecurityConfiguration.HEADER_FACILITY_ID, targetOrg.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();

        HieTenantEnforcementFilter filter = new HieTenantEnforcementFilter();
        filter.doFilter(request, response, (req, res) -> fail("Filter chain must not execute without assigned facility_id"));

        assertEquals(403, response.getStatus());
    }

    @Test
    void filterBindsAssignedFacilityIdAndServiceCrossChecksJwtClaim() throws Exception {
        UUID assignedOrg = UUID.randomUUID();
        UUID mismatchedOrg = UUID.randomUUID();

        Jwt jwt = buildJwt(assignedOrg.toString());
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("PERM_patient:read"))));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/fhir/r4/Patient");
        MockHttpServletResponse response = new MockHttpServletResponse();

        HieTenantEnforcementFilter filter = new HieTenantEnforcementFilter();
        AtomicReference<UUID> observedFacility = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> observedFacility.set(HieInteropService.requireTenantFacilityId()));

        assertEquals(200, response.getStatus());
        assertEquals(assignedOrg, observedFacility.get());
        assertTrue(FacilityContext.getFacilityId().isEmpty(), "FacilityContext must be cleared after request");

        // Direct service invocation with tampered FacilityContext must also fail closed against JWT claim
        FacilityContext.setFacilityId(mismatchedOrg);
        assertThrows(AccessDeniedException.class, HieInteropService::requireTenantFacilityId);
    }

    private static Jwt buildJwt(String facilityId) {
        return new Jwt(
                "test-token",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"),
                Map.of("sub", "clinician-1", "facility_id", facilityId)
        );
    }
}
