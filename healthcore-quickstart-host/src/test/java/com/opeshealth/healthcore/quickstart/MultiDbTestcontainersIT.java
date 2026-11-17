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

/**
 * Integration test proving Opes HealthCore's 13-module Liquibase schema migrates cleanly
 * on PostgreSQL 16, Windows SQL Server 2022, and MySQL 8.4 when Docker is present.
 */
@Tag("multidb-it")
class MultiDbTestcontainersIT {

    @Test
    void liquibaseMasterMigratesOnPostgresql() throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            runLiquibase(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        }
    }

    @Test
    void liquibaseMasterMigratesOnWindowsSqlServer() throws Exception {
        try (MSSQLServerContainer<?> mssql = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense()) {
            mssql.start();
            runLiquibase(mssql.getJdbcUrl(), mssql.getUsername(), mssql.getPassword());
        }
    }

    @Test
    void liquibaseMasterMigratesOnMysql8() throws Exception {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")) {
            mysql.start();
            runLiquibase(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
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
