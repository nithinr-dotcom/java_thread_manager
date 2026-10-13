# syntax=docker/dockerfile:1.7
# Storefront edge node. Build: docker build -t storefront-edge-node:<version> .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q -DskipTests -Dspotless.check.skip=true package \
 && java -Djarmode=tools -jar target/storefront-edge-node-*.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 storefront && useradd --system --uid 10001 --gid storefront storefront
WORKDIR /app
# Layered: dependencies change rarely, so upgrades ship only the small application layer.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER storefront
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /actuator/health/liveness HTTP/1.0\r\n\r\n" >&3 && grep -q "UP" <&3'
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
