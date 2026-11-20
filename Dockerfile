# =============================================================================
# Opes HealthCore Platform — Hardened Multi-Stage Java 25 Container Image
# Author / Architect : Jude Nshome — Opes Health Systems (nshome@opesware.com)
# Website            : https://www.opeshealthsystems.com | Douala, Cameroon
# =============================================================================
# Builds the 41-module Pure Java 25 + Spring Boot 4.0 HIS platform and runs
# healthcore-quickstart-host as a non-root hardened JRE 25 container.

# -----------------------------------------------------------------------------
# Stage 1: Maven Build (Eclipse Temurin JDK 25)
# -----------------------------------------------------------------------------
FROM maven:3.9.11-eclipse-temurin-25-alpine AS builder

WORKDIR /workspace

# Copy reactor POMs and module sources
COPY pom.xml ./
COPY . ./

# Build all modules and package the executable quickstart-host JAR
RUN mvn -B -ntp -DskipTests clean package -pl healthcore-quickstart-host -am \
    && cp healthcore-quickstart-host/target/healthcore-quickstart-host-*.jar /workspace/app.jar

# -----------------------------------------------------------------------------
# Stage 2: Hardened Non-Root Runtime (Eclipse Temurin JRE 25)
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine AS runtime

LABEL org.opencontainers.image.title="Opes HealthCore HIS Platform" \
      org.opencontainers.image.description="Pure Java 25 + Spring Boot 4.0 Hospital Information System & Interoperability Platform" \
      org.opencontainers.image.vendor="Opes Health Systems" \
      org.opencontainers.image.licenses="Apache-2.0"

# Install curl for container health checks and create dedicated non-root user
RUN apk add --no-cache curl tzdata \
    && addgroup -S -g 10001 healthcore \
    && adduser -S -u 10001 -G healthcore healthcore \
    && mkdir -p /opt/healthcore /var/log/healthcore /tmp/healthcore \
    && chown -R healthcore:healthcore /opt/healthcore /var/log/healthcore /tmp/healthcore

WORKDIR /opt/healthcore
COPY --from=builder --chown=healthcore:healthcore /workspace/app.jar /opt/healthcore/app.jar

USER healthcore:healthcore

EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom -Djava.io.tmpdir=/tmp/healthcore" \
    SERVER_PORT=8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${SERVER_PORT}/actuator/health/readiness" || exit 1

ENTRYPOINT ["java", "-jar", "/opt/healthcore/app.jar"]
