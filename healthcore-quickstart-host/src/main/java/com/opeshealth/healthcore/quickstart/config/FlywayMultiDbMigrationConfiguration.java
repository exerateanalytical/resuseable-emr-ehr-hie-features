/*
 * =============================================================================
 * Opes HealthCore SDK — Multi-RDBMS Enterprise Healthcare Platform
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configures Flyway schema versioning across all supported RDBMS targets:
 * <ul>
 *     <li><b>PostgreSQL 16+</b> ({@code classpath:db/migration/postgresql})</li>
 *     <li><b>MySQL 8.4+</b> ({@code classpath:db/migration/mysql})</li>
 *     <li><b>Microsoft SQL Server 2019/2022</b> ({@code classpath:db/migration/sqlserver})</li>
 * </ul>
 * Ensures consistent DDL deployment, baseline compatibility, deterministic ordering with
 * Liquibase and JPA {@code entityManagerFactory}, and strict table/index parity across
 * every supported enterprise database engine.
 */
@Configuration
public class FlywayMultiDbMigrationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FlywayMultiDbMigrationConfiguration.class);

    public static final String HISTORY_TABLE_NAME = "flyway_schema_history";
    public static final String BASELINE_VERSION = "0";
    public static final String BASELINE_DESCRIPTION = "Opes HealthCore multi-RDBMS baseline";

    public static final List<String> VERSIONED_MIGRATION_FILES = List.of(
            "V1__fhir_core_and_audit_schema.sql",
            "V2__fhir_clinical_resources_schema.sql"
    );

    public static final Set<String> REQUIRED_FHIR_AND_CORE_TABLES = Set.of(
            "hc_outbox_events",
            "hc_inbound_idempotency",
            "hc_audit_chain_heads",
            "hc_audit_logs",
            "hc_consent_policies",
            "hc_patients",
            "hc_patient_identifiers",
            "hc_patient_demographics",
            "hc_patient_phonetics",
            "hc_patient_merges",
            "hc_practitioners",
            "hc_encounters",
            "hc_observations"
    );

    public static final Set<String> REQUIRED_FHIR_AND_CORE_INDEXES = Set.of(
            "idx_hc_outbox_claim",
            "idx_hc_audit_facility_seq",
            "idx_hc_audit_patient",
            "idx_hc_consent_patient",
            "idx_hc_patient_fac_mrn",
            "idx_hc_patient_dob_gender",
            "idx_hc_patient_fac_email",
            "idx_hc_patient_fac_active",
            "idx_hc_pat_ident_blind",
            "idx_hc_pat_demog_patient",
            "idx_hc_phonetic_block",
            "idx_hc_practitioners_fac_active",
            "idx_hc_practitioners_fac_ident",
            "idx_hc_practitioners_name",
            "idx_hc_encounters_patient_start",
            "idx_hc_observations_fac_pat_eff",
            "idx_hc_observations_fac_code_st",
            "idx_hc_observations_fac_enc",
            "idx_hc_observations_fac_ident"
    );

    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:dbo\\.)?([a-zA-Z0-9_]+)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern INDEX_NAME_PATTERN = Pattern.compile(
            "\\b(?:CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?|(?:UNIQUE\\s+)?INDEX\\s+)(idx_[a-zA-Z0-9_]+)",
            Pattern.CASE_INSENSITIVE
    );

    public enum RdbmsDialect {
        POSTGRESQL(
                "postgresql",
                "classpath:db/migration/postgresql",
                "db/migration/postgresql",
                Map.of(
                        "uuid_type", "UUID",
                        "ts_type", "TIMESTAMPTZ",
                        "clob_type", "TEXT",
                        "bool_type", "BOOLEAN",
                        "bool_true", "TRUE",
                        "bool_false", "FALSE"
                )
        ),
        MYSQL(
                "mysql",
                "classpath:db/migration/mysql",
                "db/migration/mysql",
                Map.of(
                        "uuid_type", "BINARY(16)",
                        "ts_type", "DATETIME(6)",
                        "clob_type", "LONGTEXT",
                        "bool_type", "TINYINT(1)",
                        "bool_true", "1",
                        "bool_false", "0"
                )
        ),
        SQLSERVER(
                "sqlserver",
                "classpath:db/migration/sqlserver",
                "db/migration/sqlserver",
                Map.of(
                        "uuid_type", "UNIQUEIDENTIFIER",
                        "ts_type", "DATETIMEOFFSET",
                        "clob_type", "NVARCHAR(MAX)",
                        "bool_type", "BIT",
                        "bool_true", "1",
                        "bool_false", "0"
                )
        );

        private final String vendorKey;
        private final String flywayLocation;
        private final String classpathFolder;
        private final Map<String, String> placeholders;

        RdbmsDialect(String vendorKey, String flywayLocation, String classpathFolder, Map<String, String> placeholders) {
            this.vendorKey = vendorKey;
            this.flywayLocation = flywayLocation;
            this.classpathFolder = classpathFolder;
            this.placeholders = Map.copyOf(placeholders);
        }

        public String vendorKey() {
            return vendorKey;
        }

        public String flywayLocation() {
            return flywayLocation;
        }

        public String classpathFolder() {
            return classpathFolder;
        }

        public Map<String, String> placeholders() {
            return placeholders;
        }
    }

    public record DialectMigrationSummary(
            RdbmsDialect dialect,
            String flywayLocation,
            List<String> migrationFiles,
            Set<String> discoveredTables,
            Set<String> discoveredIndexes
    ) {
    }

    /**
     * Ensures deterministic startup ordering when both Liquibase and Flyway are active
     * and guarantees JPA {@code entityManagerFactory} schema validation runs after Flyway.
     */
    @Bean
    public static BeanFactoryPostProcessor flywayAndEntityManagerOrderingPostProcessor() {
        return (ConfigurableListableBeanFactory beanFactory) -> {
            if (beanFactory.containsBeanDefinition("liquibase")) {
                for (String flywayBean : List.of("healthCoreFlyway", "flyway", "flywayInitializer")) {
                    if (beanFactory.containsBeanDefinition(flywayBean)) {
                        appendDependsOn(beanFactory.getBeanDefinition(flywayBean), "liquibase");
                    }
                }
            }
            if (beanFactory.containsBeanDefinition("entityManagerFactory")
                    && beanFactory.containsBeanDefinition("healthCoreFlyway")) {
                appendDependsOn(beanFactory.getBeanDefinition("entityManagerFactory"), "healthCoreFlyway");
            }
        };
    }

    private static void appendDependsOn(BeanDefinition beanDefinition, String dependencyBeanName) {
        String[] current = beanDefinition.getDependsOn();
        if (current == null || current.length == 0) {
            beanDefinition.setDependsOn(dependencyBeanName);
            return;
        }
        List<String> updated = new ArrayList<>(Arrays.asList(current));
        if (!updated.contains(dependencyBeanName)) {
            updated.add(dependencyBeanName);
            beanDefinition.setDependsOn(updated.toArray(String[]::new));
        }
    }

    @Bean
    @ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
    public Flyway healthCoreFlyway(
            DataSource dataSource,
            @Value("${spring.flyway.baseline-on-migrate:true}") boolean baselineOnMigrate,
            @Value("${spring.flyway.validate-on-migrate:true}") boolean validateOnMigrate,
            @Value("${spring.flyway.table:" + HISTORY_TABLE_NAME + "}") String historyTable) {
        RdbmsDialect dialect = resolveDialect(dataSource);
        FluentConfiguration fluentConfig = buildFluentConfiguration(
                dataSource,
                dialect,
                baselineOnMigrate,
                validateOnMigrate,
                historyTable
        );
        Flyway flyway = fluentConfig.load();
        MigrateResult result = flyway.migrate();
        log.info("Flyway schema versioning completed for RDBMS dialect={} location={} migrationsExecuted={} targetSchemaVersion={}",
                dialect.name(),
                dialect.flywayLocation(),
                result.migrationsExecuted,
                result.targetSchemaVersion);
        return flyway;
    }

    /**
     * Resolves the target {@link RdbmsDialect} from a JDBC URL or database product name.
     */
    public static RdbmsDialect resolveDialect(String jdbcUrlOrProductName) {
        if (jdbcUrlOrProductName == null || jdbcUrlOrProductName.isBlank()) {
            throw new IllegalArgumentException("JDBC URL or database product name must not be blank");
        }
        String normalized = jdbcUrlOrProductName.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("postgresql") || normalized.contains("postgres")) {
            return RdbmsDialect.POSTGRESQL;
        }
        if (normalized.contains("mysql") || normalized.contains("mariadb")) {
            return RdbmsDialect.MYSQL;
        }
        if (normalized.contains("sqlserver") || normalized.contains("microsoft sql server") || normalized.contains("mssql")) {
            return RdbmsDialect.SQLSERVER;
        }
        throw new IllegalArgumentException("Unsupported RDBMS target for Flyway schema migration: " + jdbcUrlOrProductName);
    }

    /**
     * Resolves the target {@link RdbmsDialect} by inspecting the live {@link DataSource} metadata.
     */
    public static RdbmsDialect resolveDialect(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String url = metaData != null ? metaData.getURL() : null;
            if (url != null && !url.isBlank()) {
                return resolveDialect(url);
            }
            String productName = metaData != null ? metaData.getDatabaseProductName() : null;
            if (productName != null && !productName.isBlank()) {
                return resolveDialect(productName);
            }
        } catch (SQLException ex) {
            log.warn("Unable to inspect DataSource JDBC metadata for Flyway dialect resolution; defaulting to POSTGRESQL: {}", ex.getMessage());
        }
        return RdbmsDialect.POSTGRESQL;
    }

    /**
     * Builds a deterministic Flyway {@link FluentConfiguration} for the specified {@link DataSource} and {@link RdbmsDialect}.
     */
    public static FluentConfiguration buildFluentConfiguration(
            DataSource dataSource,
            RdbmsDialect dialect,
            boolean baselineOnMigrate,
            boolean validateOnMigrate,
            String historyTable) {
        Objects.requireNonNull(dialect, "dialect must not be null");
        String resolvedTable = (historyTable == null || historyTable.isBlank()) ? HISTORY_TABLE_NAME : historyTable.trim();
        FluentConfiguration config = Flyway.configure()
                .locations(dialect.flywayLocation())
                .table(resolvedTable)
                .baselineOnMigrate(baselineOnMigrate)
                .baselineVersion(BASELINE_VERSION)
                .baselineDescription(BASELINE_DESCRIPTION)
                .validateOnMigrate(validateOnMigrate)
                .outOfOrder(false)
                .placeholders(dialect.placeholders());
        if (dataSource != null) {
            config.dataSource(dataSource);
        }
        return config;
    }

    /**
     * Programmatically executes Flyway migrations against the supplied {@link DataSource}.
     */
    public static MigrateResult migrateDataSource(DataSource dataSource) {
        RdbmsDialect dialect = resolveDialect(dataSource);
        Flyway flyway = buildFluentConfiguration(dataSource, dialect, true, true, HISTORY_TABLE_NAME).load();
        return flyway.migrate();
    }

    /**
     * Inspects the classpath SQL migration files for the given {@link RdbmsDialect} and extracts
     * all declared tables and indexes to verify cross-RDBMS schema consistency.
     */
    public static DialectMigrationSummary inspectDialectMigrations(RdbmsDialect dialect) {
        Objects.requireNonNull(dialect, "dialect must not be null");
        Set<String> tables = new LinkedHashSet<>();
        Set<String> indexes = new LinkedHashSet<>();

        for (String migrationFile : VERSIONED_MIGRATION_FILES) {
            String resourcePath = dialect.classpathFolder() + "/" + migrationFile;
            ClassPathResource resource = new ClassPathResource(resourcePath);
            if (!resource.exists()) {
                throw new IllegalStateException("Missing Flyway migration script on classpath: " + resourcePath);
            }
            String sql = readResourceContent(resource, resourcePath);
            Matcher tableMatcher = CREATE_TABLE_PATTERN.matcher(sql);
            while (tableMatcher.find()) {
                tables.add(tableMatcher.group(1).toLowerCase(Locale.ROOT));
            }
            Matcher indexMatcher = INDEX_NAME_PATTERN.matcher(sql);
            while (indexMatcher.find()) {
                indexes.add(indexMatcher.group(1).toLowerCase(Locale.ROOT));
            }
        }

        return new DialectMigrationSummary(
                dialect,
                dialect.flywayLocation(),
                VERSIONED_MIGRATION_FILES,
                Set.copyOf(tables),
                Set.copyOf(indexes)
        );
    }

    /**
     * Verifies that PostgreSQL, MySQL, and SQL Server Flyway migration scripts all define the exact
     * same canonical set of FHIR R4 clinical, persistence, and audit tables and indexes.
     */
    public static Map<RdbmsDialect, DialectMigrationSummary> verifyCrossDialectSchemaParity() {
        Map<RdbmsDialect, DialectMigrationSummary> summaries = new LinkedHashMap<>();
        for (RdbmsDialect dialect : RdbmsDialect.values()) {
            DialectMigrationSummary summary = inspectDialectMigrations(dialect);
            if (!summary.discoveredTables().containsAll(REQUIRED_FHIR_AND_CORE_TABLES)) {
                Set<String> missingTables = new LinkedHashSet<>(REQUIRED_FHIR_AND_CORE_TABLES);
                missingTables.removeAll(summary.discoveredTables());
                throw new IllegalStateException("Flyway dialect " + dialect + " is missing required tables: " + missingTables);
            }
            if (!summary.discoveredIndexes().containsAll(REQUIRED_FHIR_AND_CORE_INDEXES)) {
                Set<String> missingIndexes = new LinkedHashSet<>(REQUIRED_FHIR_AND_CORE_INDEXES);
                missingIndexes.removeAll(summary.discoveredIndexes());
                throw new IllegalStateException("Flyway dialect " + dialect + " is missing required indexes: " + missingIndexes);
            }
            summaries.put(dialect, summary);
        }
        return Map.copyOf(summaries);
    }

    private static String readResourceContent(ClassPathResource resource, String resourcePath) {
        try (InputStream inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read Flyway migration script: " + resourcePath, ex);
        }
    }
}
