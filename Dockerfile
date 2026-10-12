# syntax=docker/dockerfile:1

# ---- build: JDK + Maven wrapper. Dependencies are resolved in their own layer so source edits don't re-download them.
FROM eclipse-temurin:21.0.12_8-jdk-noble AS build
WORKDIR /src

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY config/ config/
COPY seat-service/pom.xml seat-service/pom.xml
# The parent lists every module; the burst tool's pom must exist even though only seat-service is built here.
COPY burst/pom.xml burst/pom.xml
RUN chmod +x mvnw && ./mvnw -B -q -pl seat-service -am dependency:go-offline

COPY seat-service/src seat-service/src
# Tests, Spotless and Checkstyle run in CI (`./mvnw verify`, which needs Docker for Testcontainers); the image build
# only packages.
RUN ./mvnw -B -q -pl seat-service -am package -DskipTests -Dcheckstyle.skip=true -Dspotless.check.skip=true \
 && java -Djarmode=tools -jar seat-service/target/seat-service.jar extract --layers --launcher --destination /extracted

# ---- run: JRE only, non-root, Spring Boot layered jar (dependencies change least, so they come first).
FROM eclipse-temurin:21.0.12_8-jre-alpine
RUN addgroup -S app && adduser -S -G app -u 10001 app
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001

ENV PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080

# Same target as the platform health check: readiness, which includes the database (on its own small pool).
HEALTHCHECK --interval=10s --timeout=3s --start-period=90s --retries=3 \
    CMD wget -q -O /dev/null "http://127.0.0.1:${PORT}/readyz" || exit 1

# exec: java becomes PID 1 and receives SIGTERM directly, which triggers graceful shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
