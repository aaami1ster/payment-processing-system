# Multi-stage build: compile with Maven, run on JRE 21
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# BusyBox already provides wget; do not apk-add GNU wget (CVE-2025-69194, unfixed).
RUN apk upgrade --no-cache
COPY --from=build /workspace/target/payment-processing-system-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
