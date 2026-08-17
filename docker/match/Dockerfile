FROM eclipse-temurin:21-jre

# Paper 26.2 build #112, matching the paper-api 26.2.build.112-stable the
# plugin is compiled against. Override with --build-arg PAPER_URL.
ARG PAPER_URL=https://fill-data.papermc.io/v1/objects/bd3a58cf96874e5ea6643f5f6fe9b4f5bf9e34b795fa078c2f0ee8b98b2f907e/paper-26.2-112.jar

WORKDIR /server

COPY server.properties server.properties
COPY eula.txt eula.txt
COPY config/ config/
COPY plugins/ plugins/

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL -o paper.jar "${PAPER_URL}"

EXPOSE 25565

ENTRYPOINT ["java", "-Xms512M", "-Xmx1G", "-jar", "paper.jar", "nogui"]
