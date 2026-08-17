package xyz.pyxismc.tournament.velocity.provision;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;

import xyz.pyxismc.tournament.common.message.ProvisionRequest;
import xyz.pyxismc.tournament.velocity.docker.DockerEnvironment;
import xyz.pyxismc.tournament.velocity.docker.DockerException;
import xyz.pyxismc.tournament.velocity.docker.DockerGateway;

/**
 * Real provisioner: starts a temporary Docker container from a template
 * image, waits for it to get an IP on the configured network, then registers
 * it in Velocity as a dynamic server. The Paper plugin inside the container
 * picks up its server id from the {@code TOURNAMENT_SERVER_ID} environment
 * variable.
 */
public final class DockerProvisioner implements Provisioner {

    private static final long RETRY_INTERVAL_MS = 500;

    private final DockerGateway gateway;
    private final ServerRegistry registry;
    private final DockerEnvironment environment;
    private final Duration startupTimeout;
    private final String serverIdPrefix;
    private final String image;
    private final String network;
    private final int serverPort;
    private final Logger logger;

    public DockerProvisioner(
            DockerGateway gateway,
            ServerRegistry registry,
            DockerEnvironment environment,
            Duration startupTimeout,
            String serverIdPrefix,
            String image,
            String network,
            int serverPort,
            Logger logger
    ) {
        this.gateway = gateway;
        this.registry = registry;
        this.environment = environment;
        this.startupTimeout = startupTimeout;
        this.serverIdPrefix = serverIdPrefix;
        this.image = image;
        this.network = network;
        this.serverPort = serverPort;
        this.logger = logger;
    }

    @Override
    public ProvisionResult provision(ProvisionRequest request) throws ProvisionException {
        String serverId = serverIdPrefix + "-" + shortId(request.matchId());
        this.logger.info("Provisioning Docker container {} for match {} from image {}",
                serverId, request.matchId(), this.image);
        this.gateway.startContainer(serverId, this.image, this.network, this.environment.envArgs(serverId));

        String ip = awaitContainerIp(serverId);
        InetSocketAddress address = new InetSocketAddress(ip, this.serverPort);
        this.registry.register(serverId, address);
        this.logger.info("Registered match server {} at {}", serverId, address);
        return new ProvisionResult(serverId);
    }

    private String awaitContainerIp(String serverId) throws ProvisionException {
        long deadline = System.nanoTime() + this.startupTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                return this.gateway.inspectIp(serverId);
            } catch (DockerException e) {
                sleep();
            }
        }
        throw new ProvisionException("container " + serverId + " did not get an IP within "
                + this.startupTimeout.toSeconds() + "s");
    }

    private void sleep() {
        try {
            Thread.sleep(RETRY_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProvisionException("interrupted while waiting for container IP");
        }
    }

    private static String shortId(UUID matchId) {
        return matchId.toString().substring(0, 8);
    }

    /** Convenience for the plugin: server id for a given match. */
    public String serverIdFor(UUID matchId) {
        return this.serverIdPrefix + "-" + shortId(matchId);
    }
}
