package xyz.pyxismc.tournament.velocity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

class TournamentConfigTest {

    private static ConfigurationNode parse(String yaml) throws Exception {
        Path file = Files.createTempFile("tournament-config-test", ".yml");
        Files.writeString(file, yaml);
        return YamlConfigurationLoader.builder().path(file).build().load();
    }

    @Test
    void defaultsMatchSpec() {
        TournamentConfig config = TournamentConfig.defaults();

        assertEquals(3, config.tournament().maxTeamsPerMatch());
        assertEquals(3, config.tournament().playersPerTeam());
        assertEquals("game", config.server().template());
        assertEquals(Duration.ofSeconds(30), config.server().startupTimeout());
        assertEquals(Duration.ofSeconds(15), config.server().shutdownTimeout());
        assertEquals(false, config.server().docker().enabled());
        assertEquals("tournament-gameserver", config.server().docker().image());
        assertEquals("tournament", config.server().docker().network());
        assertEquals(25565, config.server().docker().port());
        assertEquals("docker", config.server().docker().command());
        assertEquals("localhost", config.redis().host());
        assertEquals(6379, config.redis().port());
        assertEquals("", config.redis().password());
        assertEquals("localhost", config.database().host());
        assertEquals(5432, config.database().port());
        assertEquals("tournament", config.database().database());
        assertEquals("tournament", config.database().username());
        assertEquals("", config.database().password());
        assertEquals("lobby", config.lobby().server());
    }

    @Test
    void fromNodeParsesFullYaml() throws Exception {
        ConfigurationNode node = parse("""
                tournament:
                  max-teams-per-match: 4
                  players-per-team: 2
                server:
                  template: "custom-template"
                  startup-timeout: 60
                  shutdown-timeout: 5
                  docker:
                    enabled: true
                    image: "pyxismc/match"
                    network: "arena"
                    port: 25566
                    command: "podman"
                redis:
                  host: redis.internal
                  port: 7000
                  password: "s3cret"
                database:
                  host: db.internal
                  port: 5433
                  database: tntwars
                  username: admin
                  password: "dbpass"
                lobby:
                  server: hub
                """);

        TournamentConfig config = TournamentConfig.fromNode(node);

        assertEquals(4, config.tournament().maxTeamsPerMatch());
        assertEquals(2, config.tournament().playersPerTeam());
        assertEquals("custom-template", config.server().template());
        assertEquals(Duration.ofSeconds(60), config.server().startupTimeout());
        assertEquals(Duration.ofSeconds(5), config.server().shutdownTimeout());
        assertEquals(true, config.server().docker().enabled());
        assertEquals("pyxismc/match", config.server().docker().image());
        assertEquals("arena", config.server().docker().network());
        assertEquals(25566, config.server().docker().port());
        assertEquals("podman", config.server().docker().command());
        assertEquals("redis.internal", config.redis().host());
        assertEquals(7000, config.redis().port());
        assertEquals("s3cret", config.redis().password());
        assertEquals("db.internal", config.database().host());
        assertEquals(5433, config.database().port());
        assertEquals("tntwars", config.database().database());
        assertEquals("admin", config.database().username());
        assertEquals("dbpass", config.database().password());
        assertEquals("hub", config.lobby().server());
    }

    @Test
    void fromNodeAppliesDefaultsForMissingKeys() throws Exception {
        ConfigurationNode node = parse("lobby:\n  server: hub\n");

        TournamentConfig config = TournamentConfig.fromNode(node);

        assertEquals(3, config.tournament().maxTeamsPerMatch());
        assertEquals(3, config.tournament().playersPerTeam());
        assertEquals("hub", config.lobby().server());
    }

    @Test
    void fromNodeAppliesDefaultsForEmptyConfig() throws Exception {
        TournamentConfig config = TournamentConfig.fromNode(parse(""));

        assertEquals(TournamentConfig.defaults(), config);
    }

    @Test
    void invalidTournamentSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TournamentConfig.TournamentSettings(0, 3));
        assertThrows(IllegalArgumentException.class, () -> new TournamentConfig.TournamentSettings(3, 0));
    }
}
