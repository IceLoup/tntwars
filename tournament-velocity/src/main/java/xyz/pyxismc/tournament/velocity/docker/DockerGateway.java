package xyz.pyxismc.tournament.velocity.docker;

import java.util.List;

/**
 * Thin abstraction over the Docker API, so provisioning logic can be tested
 * without a real daemon. Implementations are expected to throw
 * {@link DockerException} on any failure.
 */
public interface DockerGateway {

    /**
     * Starts a detached container running the given image on the given
     * network. The container name equals {@code serverId} so it can be
     * addressed later.
     */
    void startContainer(String serverId, String image, String network, List<String> envArgs);

    /**
     * Returns the container IP address on the given network.
     *
     * @throws DockerException if the container does not exist yet or its
     *         address cannot be resolved
     */
    String inspectIp(String serverId);

    /** Stops the container, waiting up to {@code timeoutSeconds}. */
    void stopContainer(String serverId, int timeoutSeconds);

    /** Removes the container (after it has been stopped). */
    void removeContainer(String serverId);
}
