package xyz.pyxismc.tournament.velocity.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Plugin configuration, loaded from {@code config.yml} in the plugin data
 * directory. Every admin-tweakable value lives here; nothing important is
 * hardcoded.
 */
public final class TournamentConfig {

    public static final String FILE_NAME = "config.yml";

    /** Tournament rules. */
    public record TournamentSettings(int maxTeamsPerMatch, int playersPerTeam) {
        public TournamentSettings {
            if (maxTeamsPerMatch < 1) {
                throw new IllegalArgumentException("maxTeamsPerMatch must be >= 1");
            }
            if (playersPerTeam < 1) {
                throw new IllegalArgumentException("playersPerTeam must be >= 1");
            }
        }
    }

    /** Temporary match server provisioning. */
    public record ServerSettings(
            String template,
            Duration startupTimeout,
            Duration shutdownTimeout,
            DockerSettings docker
    ) {
        public ServerSettings {
            Objects.requireNonNull(template, "template");
            Objects.requireNonNull(startupTimeout, "startupTimeout");
            Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
            Objects.requireNonNull(docker, "docker");
        }
    }

    /** Docker-based provisioning of temporary match servers. */
    public record DockerSettings(
            boolean enabled,
            String image,
            String network,
            int port,
            String command
    ) {
        public DockerSettings {
            Objects.requireNonNull(image, "image");
            Objects.requireNonNull(network, "network");
            Objects.requireNonNull(command, "command");
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port must be in 1..65535");
            }
        }
    }

    /** Redis connection. */
    public record RedisSettings(String host, int port, String password) {
        public RedisSettings {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(password, "password");
        }
    }

    /** PostgreSQL connection. */
    public record DatabaseSettings(String host, int port, String database, String username, String password) {
        public DatabaseSettings {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(database, "database");
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(password, "password");
        }
    }

    /** Lobby server name as registered in Velocity. */
    public record LobbySettings(String server) {
        public LobbySettings {
            Objects.requireNonNull(server, "server");
        }
    }

    private final TournamentSettings tournament;
    private final ServerSettings server;
    private final RedisSettings redis;
    private final DatabaseSettings database;
    private final LobbySettings lobby;

    public TournamentConfig(
            TournamentSettings tournament,
            ServerSettings server,
            RedisSettings redis,
            DatabaseSettings database,
            LobbySettings lobby
    ) {
        this.tournament = Objects.requireNonNull(tournament, "tournament");
        this.server = Objects.requireNonNull(server, "server");
        this.redis = Objects.requireNonNull(redis, "redis");
        this.database = Objects.requireNonNull(database, "database");
        this.lobby = Objects.requireNonNull(lobby, "lobby");
    }

    public TournamentSettings tournament() {
        return this.tournament;
    }

    public ServerSettings server() {
        return this.server;
    }

    public RedisSettings redis() {
        return this.redis;
    }

    public DatabaseSettings database() {
        return this.database;
    }

    public LobbySettings lobby() {
        return this.lobby;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TournamentConfig other)) {
            return false;
        }
        return this.tournament.equals(other.tournament)
                && this.server.equals(other.server)
                && this.redis.equals(other.redis)
                && this.database.equals(other.database)
                && this.lobby.equals(other.lobby);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.tournament, this.server, this.redis, this.database, this.lobby);
    }

    /** Defaults, matching the shipped {@code config.yml}. */
    public static TournamentConfig defaults() {
        return new TournamentConfig(
                new TournamentSettings(3, 3),
                new ServerSettings(
                        "game",
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(15),
                        new DockerSettings(false, "tournament-gameserver", "tournament", 25565, "docker")),
                new RedisSettings("localhost", 6379, ""),
                new DatabaseSettings("localhost", 5432, "tournament", "tournament", ""),
                new LobbySettings("lobby"));
    }

    /**
     * Loads the config from the plugin data directory, writing the default
     * config file when missing. Falls back to defaults on any error.
     */
    public static TournamentConfig load(Path dataDirectory, Logger logger) {
        try {
            Files.createDirectories(dataDirectory);
            Path configFile = dataDirectory.resolve(FILE_NAME);
            if (Files.notExists(configFile)) {
                writeDefaultConfig(configFile);
            }
            YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                    .path(configFile)
                    .build();
            return fromNode(loader.load());
        } catch (IOException e) {
            logger.warn("Failed to load configuration, using defaults: {}", e.toString());
            return defaults();
        }
    }

    /** Builds a config from a parsed node, applying defaults for missing keys. */
    public static TournamentConfig fromNode(ConfigurationNode node) {
        TournamentConfig defaults = defaults();

        ConfigurationNode tournamentNode = node.node("tournament");
        int maxTeamsPerMatch = tournamentNode.node("max-teams-per-match")
                .getInt(defaults.tournament().maxTeamsPerMatch());
        int playersPerTeam = tournamentNode.node("players-per-team")
                .getInt(defaults.tournament().playersPerTeam());

        ConfigurationNode serverNode = node.node("server");
        String template = serverNode.node("template").getString(defaults.server().template());
        long startupTimeout = serverNode.node("startup-timeout")
                .getLong(defaults.server().startupTimeout().toSeconds());
        long shutdownTimeout = serverNode.node("shutdown-timeout")
                .getLong(defaults.server().shutdownTimeout().toSeconds());

        ConfigurationNode dockerNode = serverNode.node("docker");
        boolean dockerEnabled = dockerNode.node("enabled").getBoolean(defaults.server().docker().enabled());
        String dockerImage = dockerNode.node("image").getString(defaults.server().docker().image());
        String dockerNetwork = dockerNode.node("network").getString(defaults.server().docker().network());
        int dockerPort = dockerNode.node("port").getInt(defaults.server().docker().port());
        String dockerCommand = dockerNode.node("command").getString(defaults.server().docker().command());

        ConfigurationNode redisNode = node.node("redis");
        String redisHost = redisNode.node("host").getString(defaults.redis().host());
        int redisPort = redisNode.node("port").getInt(defaults.redis().port());
        String redisPassword = redisNode.node("password").getString(defaults.redis().password());

        ConfigurationNode databaseNode = node.node("database");
        String dbHost = databaseNode.node("host").getString(defaults.database().host());
        int dbPort = databaseNode.node("port").getInt(defaults.database().port());
        String dbName = databaseNode.node("database").getString(defaults.database().database());
        String dbUser = databaseNode.node("username").getString(defaults.database().username());
        String dbPassword = databaseNode.node("password").getString(defaults.database().password());

        String lobbyServer = node.node("lobby").node("server").getString(defaults.lobby().server());

        return new TournamentConfig(
                new TournamentSettings(maxTeamsPerMatch, playersPerTeam),
                new ServerSettings(
                        template,
                        Duration.ofSeconds(startupTimeout),
                        Duration.ofSeconds(shutdownTimeout),
                        new DockerSettings(dockerEnabled, dockerImage, dockerNetwork, dockerPort, dockerCommand)),
                new RedisSettings(redisHost, redisPort, redisPassword),
                new DatabaseSettings(dbHost, dbPort, dbName, dbUser, dbPassword),
                new LobbySettings(lobbyServer));
    }

    private static void writeDefaultConfig(Path configFile) throws IOException {
        YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                .path(configFile)
                .defaultOptions(options -> options.header(
                        """
                        Tournament plugin configuration.

                        tournament:
                          max-teams-per-match: maximum number of teams per match server (game mode limit)
                          players-per-team:    number of players required per team
                        server:
                          template:            name of the Docker image / template used for match servers
                          startup-timeout:     seconds to wait for a match server to start
                          shutdown-timeout:    seconds to wait for a match server to stop
                          docker:
                            enabled:           provision real Docker containers instead of simulated servers
                            image:             Docker image used to start a match container
                            network:           Docker network the match containers join (velocity, redis, db)
                            port:              Minecraft server port inside the container
                            command:           docker CLI executable
                        redis:                 Redis connection (Velocity <-> Paper communication)
                        database:              PostgreSQL connection (persistent data)
                        lobby:
                          server:              name of the lobby server registered in Velocity
                        """))
                .build();
        ConfigurationNode node = loader.createNode();
        TournamentConfig defaults = defaults();
        node.node("tournament", "max-teams-per-match").set(defaults.tournament().maxTeamsPerMatch());
        node.node("tournament", "players-per-team").set(defaults.tournament().playersPerTeam());
        node.node("server", "template").set(defaults.server().template());
        node.node("server", "startup-timeout").set(defaults.server().startupTimeout().toSeconds());
        node.node("server", "shutdown-timeout").set(defaults.server().shutdownTimeout().toSeconds());
        node.node("server", "docker", "enabled").set(defaults.server().docker().enabled());
        node.node("server", "docker", "image").set(defaults.server().docker().image());
        node.node("server", "docker", "network").set(defaults.server().docker().network());
        node.node("server", "docker", "port").set(defaults.server().docker().port());
        node.node("server", "docker", "command").set(defaults.server().docker().command());
        node.node("redis", "host").set(defaults.redis().host());
        node.node("redis", "port").set(defaults.redis().port());
        node.node("redis", "password").set(defaults.redis().password());
        node.node("database", "host").set(defaults.database().host());
        node.node("database", "port").set(defaults.database().port());
        node.node("database", "database").set(defaults.database().database());
        node.node("database", "username").set(defaults.database().username());
        node.node("database", "password").set(defaults.database().password());
        node.node("lobby", "server").set(defaults.lobby().server());
        loader.save(node);
    }
}
