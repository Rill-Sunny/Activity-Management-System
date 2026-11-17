# Build the executable JAR with deploy/scripts/build-image.ps1 first.
# Exact Java 23 release; the approval-gated verifier records the resolved digest.
ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:23.0.2_7-jre-noble@sha256:482448f7d10c123f7453a2ef54035a315399547fdfc8339c37b69995845b8738
FROM ${JAVA_RUNTIME_IMAGE}
WORKDIR /app
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home app
ARG SOURCE_REVISION=unrecorded
LABEL org.opencontainers.image.title="Limited Activities System" org.opencontainers.image.version="1.0.0" org.opencontainers.image.revision="${SOURCE_REVISION}"
COPY --chown=10001:10001 target/limited-activities-system-1.0.0.jar /app/app.jar
USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=docker
EXPOSE 8080 9091
STOPSIGNAL SIGTERM
ENTRYPOINT ["java", "-Dloader.main=com.limitactivitysystem.deployment.ContainerEntrypoint", "-cp", "/app/app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"]
CMD ["run"]
