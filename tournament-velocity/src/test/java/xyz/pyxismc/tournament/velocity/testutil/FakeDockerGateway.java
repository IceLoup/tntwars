package xyz.pyxismc.tournament.velocity.testutil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import xyz.pyxismc.tournament.velocity.docker.DockerException;
import xyz.pyxismc.tournament.velocity.docker.DockerGateway;

/**
 * In-memory {@link DockerGateway} for tests. Configure the IPs to hand out
 * with {@link #setIps(String...)}; an empty queue makes {@link #inspectIp}
 * fail like a container that never comes up.
 */
public final class FakeDockerGateway implements DockerGateway {

    private final Deque<String> ips = new ArrayDeque<>();
    private final List<String> started = new ArrayList<>();
    private final List<String> stopped = new ArrayList<>();
    private final List<String> removed = new ArrayList<>();
    private List<String> lastEnvArgs = List.of();
    private String lastImage;
    private String lastNetwork;
    private int inspectCalls;

    /**
     * Configures the IPs handed out by successive {@link #inspectIp} calls.
     * {@code null} entries simulate a container that is not up yet (empty
     * string is used as sentinel since the backing deque forbids nulls).
     */
    public void setIps(String... ips) {
        this.ips.clear();
        for (String ip : ips) {
            this.ips.add(ip == null ? "" : ip);
        }
    }

    @Override
    public void startContainer(String serverId, String image, String network, List<String> envArgs) {
        this.started.add(serverId);
        this.lastImage = image;
        this.lastNetwork = network;
        this.lastEnvArgs = List.copyOf(envArgs);
    }

    @Override
    public String inspectIp(String serverId) {
        this.inspectCalls++;
        String ip = this.ips.poll();
        if (ip == null || ip.isEmpty()) {
            throw new DockerException("container " + serverId + " has no IP yet");
        }
        return ip;
    }

    @Override
    public void stopContainer(String serverId, int timeoutSeconds) {
        this.stopped.add(serverId);
    }

    @Override
    public void removeContainer(String serverId) {
        this.removed.add(serverId);
    }

    public List<String> started() {
        return this.started;
    }

    public List<String> stopped() {
        return this.stopped;
    }

    public List<String> removed() {
        return this.removed;
    }

    public List<String> lastEnvArgs() {
        return this.lastEnvArgs;
    }

    public String lastImage() {
        return this.lastImage;
    }

    public String lastNetwork() {
        return this.lastNetwork;
    }

    public int inspectCalls() {
        return this.inspectCalls;
    }
}
