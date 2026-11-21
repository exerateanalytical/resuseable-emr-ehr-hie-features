package com.example.healthcore.lab;

import com.opeshealth.healthcore.diagnostics.core.domain.DiagnosticsCoreEntities.*;
import com.opeshealth.healthcore.diagnostics.core.service.DiagnosticsCoreService;
import com.opeshealth.healthcore.diagnostics.core.service.DiagnosticsCoreService.*;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The real standalone application's Spring context and PostgreSQL schema.
 * This verifies the diagnostics-core persistence lifecycle, NOT a fully certified
 * LIS. HTTP/OIDC, barcode specimen, analyzer/QC and signed-report workflows
 * remain separate release gates.
 */
@Tag("workflow-it")
@Testcontainers
@SpringBootTest(classes = DiagnosticLaboratoryApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class IndependentWorkflowIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry props) {
        props.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        props.add("spring.datasource.username", POSTGRES::getUsername);
        props.add("spring.datasource.password", POSTGRES::getPassword);
        props.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        props.add("spring.liquibase.enabled", () -> "true");
    }

    @Autowired
    private DiagnosticsCoreService diagnostics;

    @AfterEach
    void clearAuthenticatedFacility() {
        FacilityContext.clear();
    }

    @Test
    void persistsReferralOrderCriticalReadbackReleaseAndAmendmentAcrossConnections() throws Exception {
        UUID facility = UUID.randomUUID();
        FacilityContext.setFacilityId(facility);

        ReferringPartnerEntity partner = diagnostics.registerReferringPartner(
                new RegisterReferringPartnerRequest("REF-01", "Synthetic Clinic", "CLINIC", "PORTAL"));
        assertEquals(facility, partner.getFacilityId());

        DiagnosticCatalogItemEntity potassium = diagnostics.registerCatalogItem(
                new RegisterCatalogItemRequest(
                        "LOINC-2823-3", "Serum potassium", "LABORATORY", false, "mmol/L",
                        new BigDecimal("3.5"), new BigDecimal("5.1"),
                        new BigDecimal("2.5"), new BigDecimal("6.2"), 60, null));
        assertEquals(facility, potassium.getFacilityId());

        DiagnosticServiceOrderEntity order = diagnostics.createOrder(
                new CreateDiagnosticOrderRequest(
                        "LAB-PG-001", "LABORATORY", potassium.getCatalogCode(),
                        "SYNTHETIC-CLIENT-01", partner.getPartnerCode(), 60));
        assertEquals("REGISTERED", order.getStatus());

        CriticalValueNotificationEntity critical = diagnostics.escalateCriticalResult(
                new EscalateCriticalResultRequest(order.getId(), new BigDecimal("6.9"),
                        "Synthetic critical potassium above configured threshold", "clinician-01"));
        assertEquals("ESCALATED", critical.getStatus());
        assertThrows(IllegalStateException.class,
                () -> diagnostics.acknowledgeCriticalReadBack(critical.getId(), "clinician-01", false));

        CriticalValueNotificationEntity ack =
                diagnostics.acknowledgeCriticalReadBack(critical.getId(), "clinician-01", true);
        assertEquals("ACKNOWLEDGED_READBACK", ack.getStatus());
        assertNotNull(ack.getAcknowledgedAt());

        DiagnosticServiceOrderEntity released = diagnostics.verifyAndReleaseReport(order.getId());
        assertEquals("VERIFIED_RELEASED", released.getStatus());
        assertNotNull(released.getVerifiedAt());

        ResultAmendmentEntity amendment = diagnostics.amendVerifiedResult(
                new AmendResultRequest(order.getId(),
                        "K+ 6.9 mmol/L", "K+ 6.8 mmol/L",
                        "Synthetic repeat verification", "scientist-01"));
        assertEquals(order.getId(), amendment.getOrderId());

        // These assertions use a separate physical JDBC connection, not mocks.
        assertEquals("AMENDED", readOrderStatusFreshConnection(order.getId(), facility));
        assertEquals("ACKNOWLEDGED_READBACK", readNotificationStatusFreshConnection(critical.getId(), facility));
    }

    @Test
    void rejectsSpecimenAndPreventsReleaseAcrossFacilitiesAndFailedQcPolicy() throws Exception {
        UUID facilityA = UUID.randomUUID();
        UUID facilityB = UUID.randomUUID();
        FacilityContext.setFacilityId(facilityA);

        DiagnosticServiceOrderEntity rejected = diagnostics.createOrder(
                new CreateDiagnosticOrderRequest(
                        "LAB-PG-REJECTED", "LABORATORY", "LOINC-2823-3",
                        "SYNTHETIC-CLIENT-02", null, 30));
        diagnostics.rejectAndRequestRecollection(rejected.getId(), "Synthetic hemolyzed sample");

        assertThrows(IllegalStateException.class,
                () -> diagnostics.verifyAndReleaseReport(rejected.getId()));
        assertEquals("REJECTED_RECOLLECT",
                readOrderStatusFreshConnection(rejected.getId(), facilityA));

        FacilityContext.setFacilityId(facilityB);
        assertThrows(NoSuchElementException.class,
                () -> diagnostics.verifyAndReleaseReport(rejected.getId()));
        assertThrows(NoSuchElementException.class,
                () -> diagnostics.rejectAndRequestRecollection(rejected.getId(), "Foreign facility"));

        FacilityContext.clear();
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> diagnostics.verifyAndReleaseReport(rejected.getId()));
    }

    private String readOrderStatusFreshConnection(UUID id, UUID facility) throws Exception {
        return readStatus("hc_diag_service_orders", id, facility);
    }

    private String readNotificationStatusFreshConnection(UUID id, UUID facility) throws Exception {
        return readStatus("hc_diag_critical_notifications", id, facility);
    }

    private String readStatus(String tableName, UUID id, UUID facility) throws Exception {
        // Table names are hard-coded from two known test helper call sites.
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = conn.prepareStatement(
                     "SELECT status FROM " + tableName + " WHERE id = ? AND facility_id = ?")) {
            statement.setObject(1, id);
            statement.setObject(2, facility);
            try (ResultSet results = statement.executeQuery()) {
                assertTrue(results.next(), "Expected persisted facility-scoped row in " + tableName);
                return results.getString("status");
            }
        }
    }
}
