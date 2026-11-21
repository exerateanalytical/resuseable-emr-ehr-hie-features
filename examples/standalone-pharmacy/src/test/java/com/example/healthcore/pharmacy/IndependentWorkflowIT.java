package com.example.healthcore.pharmacy;

import com.opeshealth.healthcore.pharmacy.commerce.domain.PharmacyCommerceEntities.*;
import com.opeshealth.healthcore.pharmacy.commerce.service.PharmacyCommerceService;
import com.opeshealth.healthcore.pharmacy.commerce.service.PharmacyCommerceService.*;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
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
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent pharmacy reference consumer with real PostgreSQL 16, Liquibase,
 * persisted supplier/product/purchase-order/FEFO dispensing and recall safety.
 * This is NOT a real e-prescription validation or controlled-substance certification.
 */
@Tag("workflow-it")
@Testcontainers
@SpringBootTest(classes = StandalonePharmacyApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IndependentWorkflowIT.FailClosedJwtFixture.class)
class IndependentWorkflowIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry props) {
        props.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        props.add("spring.datasource.username", POSTGRES::getUsername);
        props.add("spring.datasource.password", POSTGRES::getPassword);
        props.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        props.add("spring.liquibase.enabled", () -> "true");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailClosedJwtFixture {
        @Bean
        JwtDecoder testJwtDecoder() {
            return token -> { throw new JwtException("This domain IT does not authorize HTTP bearer tokens"); };
        }
    }

    @Autowired
    private PharmacyCommerceService pharmacy;

    @AfterEach
    void cleanupFacility() {
        FacilityContext.clear();
    }

    @Test
    void persistsQualifiedProcurementFefoDispensingAndRecallAcrossConnections() throws Exception {
        UUID facility = UUID.randomUUID();
        FacilityContext.setFacilityId(facility);

        PharmacyProductEntity product = pharmacy.createProduct(new CreateProductRequest(
                "PARA-500", "Paracetamol", "Synthetic generic", "500 mg", "TABLET",
                "N02BE01", false, 10, new BigDecimal("250.00")));
        SupplierEntity supplier = pharmacy.registerSupplier(new RegisterSupplierRequest(
                "SYN-SUP-01", "Synthetic Licensed Supplier", "LIC-SYN-001", true));
        PurchaseOrderEntity purchaseOrder = pharmacy.createPurchaseOrder(new CreatePurchaseOrderRequest(
                "PO-SYN-001", supplier.getId(), product.getId(), 100));
        assertEquals("OPEN", purchaseOrder.getStatus());

        PharmacyLotEntity nearExpiry = pharmacy.receiveGoods(new GoodsReceiptRequest(
                product.getId(), "Synthetic Licensed Supplier", "PO-SYN-001",
                "03400930000012", "LOT-PARA-EARLY", LocalDate.now().plusDays(35), 40));
        PharmacyLotEntity laterExpiry = pharmacy.receiveGoods(new GoodsReceiptRequest(
                product.getId(), "Synthetic Licensed Supplier", "PO-SYN-001",
                "03400930000012", "LOT-PARA-LATE", LocalDate.now().plusDays(180), 60));
        // A transaction from a previous service call may hold a detached snapshot.
        // Inspect durable state from a fresh JDBC connection instead.
        assertEquals("RECEIVED", persistedPoStatus(purchaseOrder.getId(), facility));
        assertEquals(100, persistedPoReceivedCount(purchaseOrder.getId(), facility));

        PharmacyDispenseEntity firstDispense = pharmacy.dispenseFefo(new DispensePrescriptionRequest(
                "RX-SYN-01", "SYNTHETIC-PATIENT-01", product.getId(),
                20, 0, "pharmacist-verified-test", null, 20, "Synthetic counseling note"));
        assertEquals(nearExpiry.getId(), firstDispense.getLotId());
        assertEquals("DISPENSED", firstDispense.getStatus());
        assertEquals(0, firstDispense.getTotalAmount().compareTo(new BigDecimal("5000.00")));
        assertEquals(20, persistedQuantity(nearExpiry.getId(), facility));
        assertEquals(60, persistedQuantity(laterExpiry.getId(), facility));

        List<PharmacyLotEntity> nearExpiryReport = pharmacy.findNearExpiryLots(90);
        assertEquals(1, nearExpiryReport.size());
        assertEquals(nearExpiry.getId(), nearExpiryReport.getFirst().getId());

        pharmacy.quarantineRecalledBatch("LOT-PARA-EARLY");
        assertEquals("QUARANTINED_RECALL", persistedLotStatus(nearExpiry.getId(), facility));
        PharmacyDispenseEntity secondDispense = pharmacy.dispenseFefo(new DispensePrescriptionRequest(
                "RX-SYN-02", "SYNTHETIC-PATIENT-02", product.getId(),
                30, 0, "pharmacist-verified-test", null, 30, "Synthetic recall-safe alternative lot"));
        assertEquals(laterExpiry.getId(), secondDispense.getLotId());
        assertEquals(30, persistedQuantity(laterExpiry.getId(), facility));

        assertEquals(2, persistedDispenseCount(facility));
        assertEquals("RECEIVED", persistedPoStatus(purchaseOrder.getId(), facility));
    }

    @Test
    void blocksExpiredGoodsUnqualifiedSuppliersWrongFacilityAndUnwitnessedControlledDrugs() throws Exception {
        UUID facility = UUID.randomUUID();
        FacilityContext.setFacilityId(facility);

        PharmacyProductEntity medicine = pharmacy.createProduct(new CreateProductRequest(
                "RX-SYN-CONTROL", "Synthetic controlled medicine", "Synthetic", "10 mg",
                "TABLET", "SYN-CONTROL", true, 15, new BigDecimal("1500.00")));
        SupplierEntity unqualified = pharmacy.registerSupplier(new RegisterSupplierRequest(
                "SYN-SUP-UNQUAL", "Synthetic Unqualified Distributor", "LIC-SYN-EXPIRED", false));
        assertThrows(IllegalStateException.class,
                () -> pharmacy.createPurchaseOrder(new CreatePurchaseOrderRequest(
                        "PO-BLOCKED", unqualified.getId(), medicine.getId(), 50)));
        assertThrows(IllegalStateException.class,
                () -> pharmacy.receiveGoods(new GoodsReceiptRequest(
                        medicine.getId(), "Synthetic Distributor", "PO-NONEXISTENT",
                        "03400930000012", "LOT-EXPIRED",
                        LocalDate.now().minusDays(2), 50)));

        SupplierEntity qualified = pharmacy.registerSupplier(new RegisterSupplierRequest(
                "SYN-SUP-QUAL", "Synthetic Qualified Distributor", "LIC-SYN-VALID", true));
        pharmacy.createPurchaseOrder(new CreatePurchaseOrderRequest(
                "PO-CONTROL", qualified.getId(), medicine.getId(), 50));
        PharmacyLotEntity stock = pharmacy.receiveGoods(new GoodsReceiptRequest(
                medicine.getId(), "Synthetic Qualified Distributor", "PO-CONTROL",
                "03400930000012", "LOT-CONTROL", LocalDate.now().plusDays(100), 50));

        assertThrows(SecurityException.class, () -> pharmacy.dispenseFefo(
                new DispensePrescriptionRequest("RX-CTRL-01", "SYNTHETIC-PATIENT-03",
                        medicine.getId(), 5, 0, "pharmacist-1", "pharmacist-1")));
        assertEquals(50, persistedQuantity(stock.getId(), facility));
        FacilityContext.setFacilityId(UUID.randomUUID());
        assertThrows(NoSuchElementException.class, () -> pharmacy.dispenseFefo(
                new DispensePrescriptionRequest("RX-CTRL-01", "SYNTHETIC-PATIENT-03",
                        medicine.getId(), 5, 0, "pharmacist-1", "pharmacist-2")));
        assertEquals(50, persistedQuantity(stock.getId(), facility));
    }

    private int persistedQuantity(UUID lotId, UUID facility) throws Exception {
        try (Connection connection = newConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT quantity_on_hand FROM hc_phcom_inventory_lots WHERE id = ? AND facility_id = ?")) {
            ps.setObject(1, lotId);
            ps.setObject(2, facility);
            try (ResultSet result = ps.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private String persistedLotStatus(UUID lotId, UUID facility) throws Exception {
        return singleString("hc_phcom_inventory_lots", "status", lotId, facility);
    }

    private String persistedPoStatus(UUID poId, UUID facility) throws Exception {
        return singleString("hc_phcom_purchase_orders", "status", poId, facility);
    }

    private int persistedPoReceivedCount(UUID poId, UUID facility) throws Exception {
        try (Connection connection = newConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT received_quantity FROM hc_phcom_purchase_orders WHERE id = ? AND facility_id = ?")) {
            ps.setObject(1, poId);
            ps.setObject(2, facility);
            try (ResultSet result = ps.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private String singleString(String table, String column, UUID id, UUID facility) throws Exception {
        // Table/column arguments are test-internal constants, never client input.
        try (Connection connection = newConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT " + column + " FROM " + table + " WHERE id = ? AND facility_id = ?")) {
            ps.setObject(1, id);
            ps.setObject(2, facility);
            try (ResultSet result = ps.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private int persistedDispenseCount(UUID facility) throws Exception {
        try (Connection connection = newConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT COUNT(*) FROM hc_phcom_dispenses WHERE facility_id = ?")) {
            ps.setObject(1, facility);
            try (ResultSet result = ps.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private Connection newConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
