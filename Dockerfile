# syntax=docker/dockerfile:1

# Build stage: compile and package the CLI as a single executable jar.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon :api-primary-adaptor:bootJar

# Runtime stage: JRE only, non-root user. The workspace to operate on is mounted at /workspace.
FROM eclipse-temurin:21-jre
RUN useradd --create-home --uid 10001 butler
COPY --from=build /src/api-primary-adaptor/build/libs/butler.jar /opt/butler/butler.jar
USER butler
WORKDIR /workspace
# Short-lived CLI process: favour start-up time over peak throughput.
ENTRYPOINT ["java", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-jar", "/opt/butler/butler.jar"]
CMD ["--help"]
