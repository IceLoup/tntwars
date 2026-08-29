# Tournament Lobby Server Template Image
# Build: docker build -t tournament-lobby:latest templates/lobby
# Run:   docker run -d --network tournament -p 25566:25565 tournament-lobby:latest
FROM eclipse-temurin:21-jre

# Paper 1.21.11 build #132, matching the paper-api 1.21.11-R0.1-SNAPSHOT the
# plugin is compiled against.
# Override with: docker build --build-arg PAPER_URL=... templates/lobby
ARG PAPER_URL=https://fill-data.papermc.io/v1/objects/5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba/paper-1.21.11-132.jar

WORKDIR /server

# Config and plugins copied at build time
COPY server.properties server.properties
COPY eula.txt eula.txt
COPY ops.json ops.json
COPY config/ config/
COPY plugins/ plugins/

# Download Paper jar
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL -o paper.jar "${PAPER_URL}"

EXPOSE 25565

ENTRYPOINT ["java", "-Xms512M", "-Xmx1G", "-jar", "paper.jar", "nogui"]
