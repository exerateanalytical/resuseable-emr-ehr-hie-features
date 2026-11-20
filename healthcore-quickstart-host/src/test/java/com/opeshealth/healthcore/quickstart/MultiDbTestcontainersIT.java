/*
 * =============================================================================
 * Opes HealthCore SDK — Testcontainers Multi-DB Schema & Liquibase Parity Suite
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart;

import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test proving Opes HealthCore's single authoritative Liquibase schema
 * (38 modular starter changelogs across 41 Maven modules) executes BOTH clean install
 * AND idempotent upgrade migration cleanly on PostgreSQL 16, Microsoft SQL Server 2022,
 * and MySQL 8.4 when Docker is present, plus PostgreSQL runtime concurrency primitives.
 */
@Tag("multidb-it")
class MultiDbTestcontainersIT {

    private static final List<String> REQUIRED_PLATFORM_TABLES = List.of(
            "hc_tenant_facilities",
            "hc_scheduled_appointments",
            "hc_unified_orders",
            "hc_portal_accounts",
            "hc_ris_modality_worklist",
            "hc_ed_visits",
            "hc_intops_partner_profiles",
            "hc_intops_message_ledger",
            "hc_rev_facility_tariffs",
            "hc_rev_payer_claims",
            "hc_analytics_quality_measures",
            "hc_dhis2_mappings"
    );

    @Test
    void liquibaseCleanInstallAndUpgradePassesOnPostgresql16() throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            // 1. Clean install across all 38 modular changelogs
            runLiquibase(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            int countAfterInstall = assertLiquibaseTablesAndChangelogCount(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());

            // 2. Idempotent upgrade re-run (simulates zero-downtime upgrade pass)
            runLiquibase(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            int countAfterUpgrade = assertLiquibaseTablesAndChangelogCount(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            assertEquals(countAfterInstall, countAfterUpgrade, "Upgrade re-run must remain idempotent");

            // 3. PostgreSQL runtime advisory lock & SKIP LOCKED outbox concurrency check
            try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                 Statement st = conn.createStatement()) {
                try (ResultSet rs = st.executeQuery("SELECT pg_try_advisory_xact_lock(hashtext('dhis2-weekly-push'))")) {
                    assertTrue(rs.next() && rs.getBoolean(1));
                }
                try (ResultSet rs = st.executeQuery("SELECT id FROM hc_transactional_outbox FOR UPDATE SKIP LOCKED LIMIT 1")) {
                    assertTrue(!rs.next());
                }
            }
        }
    }

    @Test
    void liquibaseCleanInstallAndUpgradePassesOnWindowsSqlServer2022() throws Exception {
        try (MSSQLServerContainer<?> mssql = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense()) {
            mssql.start();
            runLiquibase(mssql.getJdbcUrl(), mssql.getUsername(), mssql.getPassword());
            int countAfterInstall = assertLiquibaseTablesAndChangelogCount(mssql.getJdbcUrl(), mssql.getUsername(), mssql.getPassword());

            runLiquibase(mssql.getJdbcUrl(), mssql.getUsername(), mssql.getPassword());
            int countAfterUpgrade = assertLiquibaseTablesAndChangelogCount(mssql.getJdbcUrl(), mssql.getUsername(), mssql.getPassword());
            assertEquals(countAfterInstall, countAfterUpgrade, "SQL Server upgrade re-run must remain idempotent");
        }
    }

    @Test
    void liquibaseCleanInstallAndUpgradePassesOnMysql84() throws Exception {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")) {
            mysql.start();
            runLiquibase(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            int countAfterInstall = assertLiquibaseTablesAndChangelogCount(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());

            runLiquibase(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            int countAfterUpgrade = assertLiquibaseTablesAndChangelogCount(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            assertEquals(countAfterInstall, countAfterUpgrade, "MySQL 8.4 upgrade re-run must remain idempotent");
        }
    }

    private int assertLiquibaseTablesAndChangelogCount(String jdbcUrl, String user, String pass) throws Exception {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, pass);
             Statement st = conn.createStatement()) {
            for (String table : REQUIRED_PLATFORM_TABLES) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    assertTrue(rs.next(), "Expected Liquibase-managed table to exist: " + table);
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM databasechangelog")) {
                assertTrue(rs.next());
                int applied = rs.getInt(1);
                assertTrue(applied >= 38, "Expected at least 38 applied Liquibase changesets, got " + applied);
                return applied;
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
