# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# ShipKit container image — env-driven, no secrets baked in.
# All configuration is supplied at runtime via environment variables
# (see .env.example). Never bake credentials into this image.
# ---------------------------------------------------------------------------

# --- Build stage ------------------------------------------------------------
FROM gradle:9.0.0-jdk17 AS build
WORKDIR /home/gradle/src

# Cache dependencies first for faster incremental builds.
COPY --chown=gradle:gradle settings.gradle.kts build.gradle.kts ./
RUN gradle --no-daemon dependencies || true

# Build the fat jar.
COPY --chown=gradle:gradle . .
RUN gradle --no-daemon clean shadowJar

# --- Runtime stage ----------------------------------------------------------
FROM eclipse-temurin:17-jre AS runtime

# Run as an unprivileged user.
RUN groupadd --system shipkit && useradd --system --gid shipkit --home /app shipkit
WORKDIR /app

COPY --from=build /home/gradle/src/build/libs/shipkit-*-all.jar /app/shipkit.jar

# Non-secret default; override at runtime.
ENV SHIPKIT_PORT=8080

EXPOSE 8080
USER shipkit

# Lightweight liveness probe against the health endpoint.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD wget --quiet --tries=1 --spider "http://localhost:${SHIPKIT_PORT}/api/health" || exit 1

ENTRYPOINT ["java", "-jar", "/app/shipkit.jar"]
