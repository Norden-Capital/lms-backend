# ── Stage 1: Build ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copy Maven wrapper & pom first (layer-cache friendly)
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copy source and build the fat jar
COPY src ./src
RUN ./mvnw package -DskipTests -B

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring

# pg_dump and pg_restore, for the nightly backups. Version 16 to match the
# database — an older pg_dump refuses to copy a newer server.
RUN apk add --no-cache postgresql16-client

# Where backups are written. Compose mounts a volume here, which takes this
# ownership the first time it is created.
RUN mkdir -p /backups && chown spring:spring /backups

COPY --from=builder /app/target/*.jar app.jar

USER spring

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
