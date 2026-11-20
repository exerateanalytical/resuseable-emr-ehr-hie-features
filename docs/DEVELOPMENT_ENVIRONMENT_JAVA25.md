# OPES HealthCore — reproducible Java 25 development environment

This is an SDK developer environment, not a hospital production deployment.

## Supported setup

- Docker Desktop with Linux containers enabled (Windows users should enable WSL 2), or Docker Engine on Linux.
- VS Code with the **Dev Containers** extension, or a Codespaces environment supporting the Compose file.
- At least 8 GiB RAM for a minimal build, more for the full multi-DB/Testcontainers matrix.

In VS Code, open the repository and select **Dev Containers → Reopen in Container**.
The configuration in `.devcontainer/` builds a Java 25 + Maven image and starts PostgreSQL 16.
The container deliberately uses a **local-development-only** database credential.

Verify tools and compile all Maven reactor modules:

```bash
java -version
mvn -version
bash scripts/verify-sdk-java25.sh compile
```

Execute all ordinary reactor tests:

```bash
bash scripts/verify-sdk-java25.sh unit
```

Run the broader available verification, including Docker-dependent tests:

```bash
docker info
bash scripts/verify-sdk-java25.sh full
```

The repository also has GitHub Actions workflows for the Java 25 reactor, selected module suites, Testcontainers and SDK-pack isolation; those are the authoritative remote build results.

## Important limitations and operational controls

- This development environment provides **Java 25, Maven, Docker CLI and PostgreSQL**, but it does not establish clinical safety or full feature correctness by itself.
- The mounted Docker socket exposes highly privileged operations on the Docker host. Use it **only on a trusted personal machine**; remove the socket mount if your environment cannot safely allow it. For untrusted machines, run Docker-dependent tests exclusively in GitHub Actions.
- The main repository `docker-compose.yml` separately provides optional Keycloak, MySQL and SQL Server infrastructure. Start them only when a test requires them. Avoid simultaneously exposing duplicate PostgreSQL ports from both Compose files.
- A successful compile does **not** mean every starter is independently usable. Validate each published pack using its isolated consumer tests, database migrations, API conformance, tenant/security checks, and representative end-to-end workflows.
- Never test with identifiable real patient information.
- All clinical and regulatory claims require appropriate independent clinical/regulatory validation.
- Do not copy the demo database password or Docker socket configuration into production.
