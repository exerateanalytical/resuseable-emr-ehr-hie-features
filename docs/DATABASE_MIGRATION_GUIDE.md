# Database Migration Guide — Sole Authoritative Liquibase Engine

## 1. Migration Ownership Policy
- **Liquibase is the sole authoritative database migration engine** across all Opes HealthCore modules.
- Legacy Flyway configuration, dependencies, and SQL migration scripts have been permanently removed to prevent split-brain schema state.
- Master changelog entrypoint: `classpath:db/changelog/db.changelog-master.xml` inside `healthcore-quickstart-host` (aggregating 38 modular starter changelogs across 41 Maven modules).

## 2. Supported RDBMS Engines & Profiles
| Database Engine | Minimum Version | Spring Profile | JDBC Driver |
| :--- | :--- | :--- | :--- |
| PostgreSQL (Primary) | 16+ | `postgres` | `org.postgresql:postgresql` |
| Microsoft SQL Server | 2022+ | `mssql` | `com.microsoft.sqlserver:mssql-jdbc` |
| MySQL | 8.4+ | `mysql` | `com.mysql:mysql-connector-j` |

## 3. Clean Install, Upgrade & Disaster Recovery Drill Procedure
1. **Back up the target database** prior to any upgrade using `./scripts/backup-postgres.sh` (which writes a compressed dump, SHA-256 checksum, optional GPG encryption, and JSON DR manifest) or native SQL Server `BACKUP DATABASE` / `mysqldump`.
2. **Verify recoverability regularly** using `./scripts/restore-drill-verify.sh <dump-file>`, which validates SHA-256 integrity, restores into an isolated drill database, verifies `databasechangelog` and `hc_*` clinical tables, and reports measured RTO in seconds.
3. **Never edit an already-deployed Liquibase `<changeSet>`**; always append a new sequentially numbered changelog file (e.g., `002-*.xml`) and register it in `db.changelog-master.xml`.
4. **Use portable cross-DB type aliases** in every changelog: `${uuid_type}`, `${ts_type}`, `${clob_type}`, `${bool_type}`.
5. **PostgreSQL Row-Level Security (RLS) Activation:**
   - Run Liquibase migrations using the schema-owner role.
   - Apply `postgres/enable-rls-policies.sql` so tables enforce `facility_id = NULLIF(current_setting('app.current_facility_id', true), '')::uuid`.
   - Connect the application runtime using a **non-superuser** PostgreSQL role (superusers bypass RLS) and set `healthcore.postgres.rls.enabled=true`.
   - `PostgresRlsSessionAspect` binds `app.current_facility_id` per transaction (binding `''` fail-closed if absent) and clears it in a `finally` block.

## 4. Verification Commands (JDK 25 + Docker Required)
```bash
# Unit & static Liquibase parity checks across all 41 modules
mvn -B clean verify -DskipITs=true

# Multi-DB Testcontainers migration verification (PostgreSQL, SQL Server, MySQL)
mvn -B verify -pl healthcore-quickstart-host -am -Dit.test=MultiDbTestcontainersIT

# Build hardened Java 25 container image
docker build --tag opes-healthcore/quickstart-host:local .
```
