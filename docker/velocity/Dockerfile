FROM eclipse-temurin:21-jre

# Velocity 3.4.0-SNAPSHOT build #476, matching the velocity-api 3.4.0-SNAPSHOT
# the plugin is compiled against. Override with --build-arg VELOCITY_URL.
ARG VELOCITY_URL=https://fill-data.papermc.io/v1/objects/74a9fe7ea6519ffeca1d41bfa1e93030fd46c54a7e1f9b52a9790ebde9b8b4be/velocity-3.4.0-SNAPSHOT-476.jar

WORKDIR /velocity

COPY velocity.toml velocity.toml
COPY plugins/ plugins/

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL -o velocity.jar "${VELOCITY_URL}"

EXPOSE 25577

ENTRYPOINT ["java", "-Xms512M", "-Xmx1G", "-jar", "velocity.jar"]
