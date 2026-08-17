package xyz.pyxismc.tournament.velocity.provision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import xyz.pyxismc.tournament.common.message.ProvisionRequest;
import xyz.pyxismc.tournament.velocity.docker.DockerEnvironment;
import xyz.pyxismc.tournament.velocity.testutil.FakeDockerGateway;
import xyz.pyxismc.tournament.velocity.testutil.FakeServerRegistry;

class DockerProvisionerTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(DockerProvisionerTest.class);

    private FakeDockerGateway gateway;
    private FakeServerRegistry registry;
    private DockerProvisioner provisioner;

    @BeforeEach
    void setUp() {
        this.gateway = new FakeDockerGateway();
        this.registry = new FakeServerRegistry();
        this.provisioner = new DockerProvisioner(
                this.gateway,
                this.registry,
                new DockerEnvironment("redis.internal", 6379, "s3cret"),
                Duration.ofSeconds(5),
                "game",
                "tournament-match",
                "tournament",
                25565,
                LOGGER);
    }

    private static ProvisionRequest request() {
        return new ProvisionRequest(UUID.randomUUID(), "game",
                java.util.List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test
    void provisionsContainerAndRegistersItInTheProxy() {
        this.gateway.setIps("10.0.0.5");

        ProvisionResult result = this.provisioner.provision(request());

        assertTrue(result.serverId().startsWith("game-"), result.serverId());
        assertEquals(1, this.gateway.started().size());
        assertEquals(result.serverId(), this.gateway.started().get(0));
        assertEquals("tournament-match", this.gateway.lastImage());
        assertEquals("tournament", this.gateway.lastNetwork());
        assertEquals("10.0.0.5", this.registry.addressOf(result.serverId()).getHostString());
        assertEquals(25565, this.registry.addressOf(result.serverId()).getPort());
    }

    @Test
    void containerReceivesEnvironmentToReachRedisAndKnowItsIdentity() {
        this.gateway.setIps("10.0.0.5");

        ProvisionResult result = this.provisioner.provision(request());

        java.util.List<String> env = this.gateway.lastEnvArgs();
        assertTrue(env.contains("TOURNAMENT_SERVER_ID=" + result.serverId()), env.toString());
        assertTrue(env.contains("REDIS_HOST=redis.internal"), env.toString());
        assertTrue(env.contains("REDIS_PORT=6379"), env.toString());
        assertTrue(env.contains("REDIS_PASSWORD=s3cret"), env.toString());
    }

    @Test
    void waitsUntilTheContainerHasAnIp() {
        this.gateway.setIps(null, null, "10.0.0.7");

        ProvisionResult result = this.provisioner.provision(request());

        assertEquals(3, this.gateway.inspectCalls());
        assertEquals("10.0.0.7", this.registry.addressOf(result.serverId()).getHostString());
    }

    @Test
    void timesOutWhenTheContainerNeverGetsAnIp() {
        this.gateway.setIps();
        DockerProvisioner impatient = new DockerProvisioner(
                this.gateway,
                this.registry,
                new DockerEnvironment("localhost", 6379, ""),
                Duration.ofMillis(600),
                "game",
                "tournament-match",
                "tournament",
                25565,
                LOGGER);

        assertThrows(ProvisionException.class, () -> impatient.provision(request()));
        assertTrue(this.registry.size() == 0, "no server should be registered on failure");
    }
}
