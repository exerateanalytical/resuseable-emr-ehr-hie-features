# Database Migration Guide — Sole Authoritative Liquibase Engine

## 1. Migration Ownership Policy
- **Liquibase is the sole authoritative database migration engine** across all Opes HealthCore modules.
- Legacy Flyway configuration, dependencies, and SQL migration scripts have been permanently removed to prevent split-brain schema state.
- Master changelog entrypoint: `classpath:db/changelog/db.changelog-master.xml` inside `healthcore-quickstart-host` (currently aggregating 34 modular changelogs).

## 2. Supported RDBMS Engines & Profiles
| Database Engine | Minimum Version | Spring Profile | JDBC Driver |
| :--- | :--- | :--- | :--- |
| PostgreSQL (Primary) | 16+ | `postgres` | `org.postgresql:postgresql` |
| Microsoft SQL Server | 2022+ | `mssql` | `com.microsoft.sqlserver:mssql-jdbc` |
| MySQL | 8.4+ | `mysql` | `com.mysql:mysql-connector-j` |

## 3. Clean Install & Upgrade Procedure
1. **Back up the target database** prior to any upgrade (`pg_dump` / SQL Server `BACKUP DATABASE` / `mysqldump`).
2. **Never edit an already-deployed Liquibase `<changeSet>`**; always append a new sequentially numbered changelog file (e.g., `002-*.xml`) and register it in `db.changelog-master.xml`.
3. **Use portable cross-DB type aliases** in every changelog: `${uuid_type}`, `${ts_type}`, `${clob_type}`, `${bool_type}`.
4. **PostgreSQL Row-Level Security (RLS) Activation:**
   - Run Liquibase migrations using the schema-owner role.
   - Apply `postgres/enable-rls-policies.sql` so tables enforce `facility_id = NULLIF(current_setting('app.current_facility_id', true), '')::uuid`.
   - Connect the application runtime using a **non-superuser** PostgreSQL role (superusers bypass RLS) and set `healthcore.postgres.rls.enabled=true`.
   - `PostgresRlsSessionAspect` binds `app.current_facility_id` per transaction (binding `''` fail-closed if absent) and clears it in a `finally` block.

## 4. Verification Commands (JDK 25 + Docker Required)
```bash
# Unit & static Liquibase parity checks
mvn -B clean verify -DskipITs=true

# Multi-DB Testcontainers migration verification (PostgreSQL, SQL Server, MySQL)
mvn -B verify -pl healthcore-quickstart-host -am -Dit.test=MultiDbTestcontainersIT
```
