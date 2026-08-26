# Tournament Game Server Template Image
# Build: docker build -t tournament-gameserver:latest templates/gameserver
# Run:   docker run -d --network tournament \
#          -e TOURNAMENT_SERVER_ID=game-xxx \
#          -e REDIS_HOST=redis -e REDIS_PORT=6379 -e REDIS_PASSWORD= \
#          tournament-gameserver:latest
FROM eclipse-temurin:25-jre

# Paper version matching the paper-api the plugin is compiled against.
# Override with: docker build --build-arg PAPER_URL=... templates/gameserver
ARG PAPER_URL=https://fill-data.papermc.io/v1/objects/bd3a58cf96874e5ea6643f5f6fe9b4f5bf9e34b795fa078c2f0ee8b98b2f907e/paper-26.2-112.jar

WORKDIR /server

# Config and plugins copied at build time
COPY server.properties server.properties
COPY eula.txt eula.txt
COPY config/ config/
COPY plugins/ plugins/

# Download Paper jar
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL -o paper.jar "${PAPER_URL}"

EXPOSE 25565

ENTRYPOINT ["java", "-Xms512M", "-Xmx1G", "-jar", "paper.jar", "nogui"]
