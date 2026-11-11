# One image for the whole application: the Vue SPA is built first and served by Spring Boot
# (SpaWebConfig) next to the API, so browser and API share ONE origin: no CORS, no API URL baked
# into the bundle, and a reverse proxy only needs to forward everything to port 8080.
# The build context is the repository root (docker compose sets it).

# Stage 1: build the SPA
FROM node:24-alpine AS frontend

WORKDIR /app

COPY frontend/package*.json ./
RUN npm ci --legacy-peer-deps

COPY frontend/ .

# Empty = the SPA calls the origin it was loaded from (/api/...). Only set this when the API
# lives on a different origin (then also set CORS_ALLOWED_ORIGINS on the backend).
ARG VITE_API_BASE_URL=
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}

RUN npm run build

# Stage 2: build the backend JAR with the SPA inside
FROM maven:3.9-eclipse-temurin-25-alpine AS builder

# Maven profile of the build: empty = the self-host application, "saas" = the multi-tenant
# application (Spring Security, tenant isolation; docs/architecture/multi-tenancy.md). The two are
# different JARs: the self-host one contains no SaaS code (CI checks it).
ARG MAVEN_PROFILES=

WORKDIR /app

# Copy pom.xml
COPY backend/pom.xml .

# Download dependencies (cached layer). Same profile: the SaaS build has more dependencies.
RUN mvn dependency:go-offline -B ${MAVEN_PROFILES:+-P${MAVEN_PROFILES}}

# Copy source code and the built SPA (served from classpath:/static/)
COPY backend/src ./src
COPY --from=frontend /app/dist ./src/main/resources/static

# Build the application (skip tests for faster build)
RUN mvn package -DskipTests -B ${MAVEN_PROFILES:+-P${MAVEN_PROFILES}}

# Runtime stage
FROM eclipse-temurin:25.0.5_8-jre-alpine-3.24

# Spring profile the image starts with. A SaaS JAR started WITHOUT the profile "saas" would run in
# the self-host mode, that is without any authentication, so the SaaS image has it built in
# (--build-arg SPRING_PROFILE=saas, together with MAVEN_PROFILES=saas). Empty for self-host.
ARG SPRING_PROFILE=
ENV SPRING_PROFILES_ACTIVE=${SPRING_PROFILE}

# Alpine fixes that are newer than the last build of the base image (Trivy gates the build on them,
# docs/operations/supply-chain.md). `apk upgrade` is what hadolint warns about (DL3017): a pinned
# version would be removed from the repository with the next fix and break the build.
# hadolint ignore=DL3017
RUN apk upgrade --no-cache

WORKDIR /app

# Copy the built JAR from builder stage
COPY --from=builder /app/target/*.jar app.jar

RUN addgroup -S -g 1001 app && adduser -S -u 1001 -G app app \
    && mkdir -p /app/uploads /app/logs && chown app:app /app/uploads /app/logs
# Numeric UID/GID so the user resolves on any host (hadolint DL3066)
USER 1001:1001
EXPOSE 8080

# Non-secret defaults only. Database credentials are passed at runtime and never
# added to image metadata (hadolint DL3064).
ENV DB_HOST=postgres \
    DB_PORT=5432 \
    DB_NAME=billery \
    FILES_PATH=/app/uploads

# Run the application in headless mode (no display required)
# The heap follows the container's memory limit (mem_limit in Compose): 75 % of it, the rest is for
# metaspace, threads and the PDF renderer. The JVM default would be only 25 %.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.awt.headless=true", "-jar", "app.jar"]
