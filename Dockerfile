# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
# The Maven local repository is a BuildKit cache mount, not a layer: it
# persists across builds (including across dependency-version bumps that
# would otherwise bust a COPY-based layer cache) without ever being baked
# into an image layer itself. dependency:go-offline runs before the
# source is copied so this step — the slow part — only re-executes when
# pom.xml actually changes.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.title="double-entry-ledger" \
      org.opencontainers.image.description="Production-grade double-entry bookkeeping ledger service" \
      org.opencontainers.image.source="https://github.com/abel05-droid/double-entry-ledger" \
      org.opencontainers.image.version="0.1.0-SNAPSHOT"

# Alpine's busybox provides wget, which is enough for the HEALTHCHECK
# below; no need to pull in curl just for that one call.
RUN addgroup -S ledger && adduser -S ledger -G ledger
WORKDIR /app
COPY --from=build --chown=ledger:ledger /workspace/target/*.jar app.jar
USER ledger

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
