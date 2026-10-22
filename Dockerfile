FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace
COPY . .
ARG SERVICE=project-service
RUN chmod +x gradlew && ./gradlew :${SERVICE}:bootJar --no-daemon && cp ${SERVICE}/build/libs/app.jar /app.jar

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 spring
WORKDIR /app
COPY --from=builder /app.jar app.jar
USER spring
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
