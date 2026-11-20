# Development tooling only. Not a production runtime image.
# Keep this on the same Java major version as the parent Maven reactor.
FROM maven:3.9.11-eclipse-temurin-25

RUN apt-get update \
    && apt-get install -y --no-install-recommends git curl ca-certificates bash docker.io \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace/healthcore
