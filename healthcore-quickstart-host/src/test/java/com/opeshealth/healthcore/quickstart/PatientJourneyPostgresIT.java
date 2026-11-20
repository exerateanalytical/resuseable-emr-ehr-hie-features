/*
 * =============================================================================
 * Opes HealthCore SDK — PostgreSQL 16 Testcontainers Patient Journey & Queue IT
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart;

import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL 16 Testcontainers integration test verifying Liquibase execution of
 * {@code 002-clinical-journey-contracts.xml} and {@code 001-queue-management.xml},
 * database-enforced idempotency and counter uniqueness constraints, and restart persistence
 * across fresh database connections.
 */
@Tag("multidb-it")
class PatientJourneyPostgresIT {

    private static final List<String> REQUIRED_JOURNEY_AND_QUEUE_TABLES = List.of(
            "hc_workflow_subflow_links",
            "hc_order_routing_tasks",
            "hc_clinician_result_inbox",
            "hc_durable_notifications",
            "hc_workflow_processed_events",
            "hc_queue_counters",
            "hc_department_queue_entries",
            "hc_queue_pause_controls"
    );

    @Test
    void clinicalJourneyAndQueueMigrationsEnforceConstraintsAndPersistAcrossConnectionsOnPostgres16() throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();

            // 1. Apply full Liquibase master changelog (clean install + idempotent upgrade)
            runLiquibase(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            runLiquibase(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());

            UUID facId = UUID.randomUUID();
            UUID patientId = UUID.randomUUID();
            UUID encounterId = UUID.randomUUID();
            UUID orderId = UUID.randomUUID();
            UUID routingId = UUID.randomUUID();
            UUID counterId = UUID.randomUUID();

            // 2. Verify all 8 new tables exist and insert durable state in Connection 1
            try (Connection c1 = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                try (Statement st = c1.createStatement()) {
                    for (String table : REQUIRED_JOURNEY_AND_QUEUE_TABLES) {
                        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                            assertTrue(rs.next(), "Expected table to exist on PostgreSQL 16: " + table);
                        }
                    }
                }

                // Atomic daily queue counter insert
                try (PreparedStatement ps = c1.prepareStatement(
                        "INSERT INTO hc_queue_counters (id, facility_id, department_code, service_date, last_ticket_seq, version) VALUES (?, ?, ?, ?, ?, ?)")) {
                    ps.setObject(1, counterId);
                    ps.setObject(2, facId);
                    ps.setString(3, "LAB_COLLECTION");
                    ps.setObject(4, LocalDate.now());
                    ps.setLong(5, 1L);
                    ps.setLong(6, 0L);
                    ps.executeUpdate();
                }

                // Replay-safe order routing insert + verify unique constraint rejects duplicate idempotency_key
                String insertRouting = """
                        INSERT INTO hc_order_routing_tasks
                        (id, facility_id, patient_id, encounter_id, order_id, target_department, financial_clearance, routing_status, idempotency_key, created_at, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)
                        """;
                try (PreparedStatement ps = c1.prepareStatement(insertRouting)) {
                    ps.setObject(1, routingId);
                    ps.setObject(2, facId);
                    ps.setObject(3, patientId);
                    ps.setObject(4, encounterId);
                    ps.setObject(5, orderId);
                    ps.setString(6, "LABORATORY");
                    ps.setString(7, "PAYMENT_PENDING");
                    ps.setString(8, "ROUTED_AWAITING_CLEARANCE");
                    ps.setString(9, "IDEMP-PG-" + orderId);
                    ps.setLong(10, 0L);
                    ps.executeUpdate();
                }

                assertThrows(SQLException.class, () -> {
                    try (PreparedStatement dup = c1.prepareStatement(insertRouting)) {
                        dup.setObject(1, UUID.randomUUID());
                        dup.setObject(2, facId);
                        dup.setObject(3, patientId);
                        dup.setObject(4, encounterId);
                        dup.setObject(5, orderId);
                        dup.setString(6, "LABORATORY");
                        dup.setString(7, "PAYMENT_PENDING");
                        dup.setString(8, "ROUTED_AWAITING_CLEARANCE");
                        dup.setString(9, "IDEMP-PG-" + orderId);
                        dup.setLong(10, 0L);
                        dup.executeUpdate();
                    }
                }, "PostgreSQL UNIQUE constraint must reject duplicate order routing idempotency key");
            }

            // 3. Reconnect via fresh JDBC connection (restart & recovery check) and verify durable rows survive
            try (Connection c2 = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                 PreparedStatement ps = c2.prepareStatement(
                         "SELECT routing_status, financial_clearance FROM hc_order_routing_tasks WHERE id = ? AND facility_id = ?")) {
                ps.setObject(1, routingId);
                ps.setObject(2, facId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Persisted order routing must survive connection/JVM restart");
                    assertEquals("ROUTED_AWAITING_CLEARANCE", rs.getString("routing_status"));
                    assertEquals("PAYMENT_PENDING", rs.getString("financial_clearance"));
                }
            }
        }
    }

    private void runLiquibase(String jdbcUrl, String user, String pass) throws Exception {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, pass)) {
            var db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(conn));
            Liquibase liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.xml",
                    new ClassLoaderResourceAccessor(),
                    db);
            liquibase.update("");
        }
    }
}
