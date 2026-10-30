# syntax=docker/dockerfile:1

# Backend image (ADR-0011): the same image runs locally, in the CI and on the server (12-factor X).
#
# The build context is backend/. The OpenAPI contract comes from a second build context named
# "contract" (docs/api), because the build copies it into the jar (openapi.contract.dir in pom.xml):
#   docker build --build-context contract=../docs/api -t urbanpulse-backend .
# docker-compose.yaml passes it with additional_contexts.
#
# Temurin, the same distribution as the CI (JAVA_DISTRIBUTION). When JAVA_VERSION changes in ci.yml,
# change both tags too.

FROM eclipse-temurin:25.0.4.1_1-jdk-noble AS build
WORKDIR /workspace/backend
# Same layout as the repository, so ../docs/api resolves without overriding openapi.contract.dir.
COPY --from=contract openapi.yml /workspace/docs/api/openapi.yml
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
# Tests, coverage, SpotBugs and formatting belong to the test and lint jobs, not to the image.
# install-git-hooks is turned off because it runs git, which the image does not have.
# mvnw is run with sh because it is not versioned as executable.
# cp fails if the build produced more than one jar: one artifact per commit (12-factor V).
RUN --mount=type=cache,target=/root/.m2 \
    sh ./mvnw -B -ntp -P '!install-git-hooks' \
        -DskipTests -Djacoco.skip=true -Dspotbugs.skip=true -Dspotless.check.skip=true \
        package \
    && cp target/*.jar /workspace/app.jar

FROM eclipse-temurin:25.0.4.1_1-jre-noble AS runtime
LABEL org.opencontainers.image.source="https://github.com/Dalibex/urban-incidence-manager"
# The process never runs as root. The jar stays owned by root, so the process cannot modify it.
RUN groupadd --system --gid 10001 urbanpulse \
    && useradd --system --uid 10001 --gid urbanpulse --no-create-home urbanpulse
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER urbanpulse
# Documentation only: the application listens on SERVER_PORT when it is set (12-factor VII).
EXPOSE 8080
# Exec form: java is PID 1 and receives the SIGTERM of docker stop (12-factor IX).
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
