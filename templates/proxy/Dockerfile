# Tournament Proxy Template Image
# Build: docker build -t tournament-velocity:latest templates/proxy
# Run:   docker run -d -p 2920:2920 --network tournament \
#          -v /var/run/docker.sock:/var/run/docker.sock tournament-velocity:latest
FROM eclipse-temurin:21-jre

# Velocity version matching the velocity-api the plugin is compiled against.
# Override with: docker build --build-arg VELOCITY_URL=... templates/proxy
ARG VELOCITY_URL=https://fill-data.papermc.io/v1/objects/74a9fe7ea6519ffeca1d41bfa1e93030fd46c54a7e1f9b52a9790ebde9b8b4be/velocity-3.4.0-SNAPSHOT-476.jar

WORKDIR /velocity

# Config and plugins copied at build time for a ready-to-run image
COPY velocity.toml velocity.toml
COPY plugins/ plugins/

# Download Velocity jar and install Docker CLI for dynamic match server provisioning
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl docker.io \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL -o velocity.jar "${VELOCITY_URL}"

EXPOSE 2920

ENTRYPOINT ["java", "-Xms512M", "-Xmx1G", "-jar", "velocity.jar"]
