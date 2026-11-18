/*
 * =============================================================================
 * Opes HealthCore SDK — PostgreSQL Outbox, Sync & DHIS2 Actuator HealthIndicator
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Exposes Opes HealthCore operational telemetry inside Spring Boot Actuator
 * ({@code /actuator/health}): PostgreSQL connectivity, pending &amp; DEAD_LETTER
 * outbox counts, open offline sync conflict queue depth, and failed DHIS2 batches.
 */
@Component("healthCoreDiagnostics")
public class PostgresHealthCoreDiagnosticsIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;

    public PostgresHealthCoreDiagnosticsIndicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Health health() {
        try {
            String engine = detectEngine();
            long pendingOutbox = safeCount("SELECT COUNT(*) FROM hc_outbox_events WHERE status IN ('PENDING','FAILED')");
            long deadLetterOutbox = safeCount("SELECT COUNT(*) FROM hc_outbox_events WHERE status = 'DEAD_LETTER'");
            long openSyncConflicts = safeCount("SELECT COUNT(*) FROM hc_sync_conflicts WHERE status = 'OPEN'");
            long dhis2Failed = safeCount("SELECT COUNT(*) FROM hc_dhis2_sync_logs WHERE status = 'FAILED'");

            Health.Builder builder = deadLetterOutbox > 25 ? Health.down() : Health.up();
            return builder
                    .withDetail("databaseEngine", engine)
                    .withDetail("outboxPendingCount", pendingOutbox)
                    .withDetail("outboxDeadLetterCount", deadLetterOutbox)
                    .withDetail("openSyncConflictCount", openSyncConflicts)
                    .withDetail("dhis2FailedCount", dhis2Failed)
                    .build();
        } catch (Exception ex) {
            return Health.down(ex).build();
        }
    }

    private long safeCount(String sql) {
        try {
            Long val = jdbcTemplate.queryForObject(sql, Long.class);
            return val != null ? val : 0L;
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private String detectEngine() {
        DataSource ds = jdbcTemplate.getDataSource();
        if (ds == null) return "UNKNOWN";
        try (Connection c = ds.getConnection()) {
            return c.getMetaData().getDatabaseProductName() + " " + c.getMetaData().getDatabaseProductVersion();
        } catch (Exception ex) {
            return "UNAVAILABLE";
        }
    }
}
