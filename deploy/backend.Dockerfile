# syntax=docker/dockerfile:1
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY service ./service
WORKDIR /workspace/service
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 atlas \
    && mkdir -p /data/files /data/previous-reports \
    && chown -R atlas:atlas /data /app
COPY --from=build \
    /workspace/service/atlas-bootstrap/target/atlas-bootstrap-0.1.0-SNAPSHOT.jar \
    /app/atlas.jar
USER atlas
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/atlas.jar"]
