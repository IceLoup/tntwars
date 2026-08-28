FROM eclipse-temurin:21-jre

# Velocity 3.5.1 build #615, matching the velocity-api 3.5.1
# the plugin is compiled against. Override with --build-arg VELOCITY_URL.
ARG VELOCITY_URL=https://fill-data.papermc.io/v1/objects/b4e3164df5377346854dc6cb9e6a78022b1946ff69e89676313f5f6f1c6f0fb3/velocity-3.5.1-615.jar

WORKDIR /velocity

COPY velocity.toml velocity.toml
COPY plugins/ plugins/

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL -o velocity.jar "${VELOCITY_URL}"

EXPOSE 25577

ENTRYPOINT ["java", "-Xms512M", "-Xmx1G", "-jar", "velocity.jar"]
