package xyz.pyxismc.tournament.velocity.docker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

/**
 * {@link DockerGateway} implementation that shells out to the {@code docker}
 * CLI. Keeps the plugin dependency-free: no Docker SDK, just a configured
 * executable.
 */
public final class CliDockerGateway implements DockerGateway {

    private final String command;
    private final Logger logger;

    public CliDockerGateway(String command, Logger logger) {
        this.command = command;
        this.logger = logger;
    }

    @Override
    public void startContainer(String serverId, String image, String network, List<String> envArgs) {
        List<String> args = new ArrayList<>(List.of(
                this.command, "run", "-d",
                "--name", serverId,
                "--network", network));
        args.addAll(envArgs);
        args.add(image);
        run(args);
    }

    @Override
    public String inspectIp(String serverId) {
        String output = run(List.of(
                this.command, "inspect",
                "--format", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}",
                serverId));
        String ip = output == null ? "" : output.trim();
        if (ip.isEmpty()) {
            throw new DockerException("no IP address for container " + serverId);
        }
        return ip;
    }

    @Override
    public void stopContainer(String serverId, int timeoutSeconds) {
        run(List.of(this.command, "stop", "-t", Integer.toString(timeoutSeconds), serverId));
    }

    @Override
    public void removeContainer(String serverId) {
        run(List.of(this.command, "rm", "-f", serverId));
    }

    private String run(List<String> args) {
        this.logger.debug("docker {}", args);
        try {
            Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new DockerException("docker command timed out: " + args);
            }
            if (process.exitValue() != 0) {
                throw new DockerException("docker command failed (" + process.exitValue() + "): " + args + " -> " + output);
            }
            return output;
        } catch (IOException e) {
            throw new DockerException("cannot run docker command: " + args, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DockerException("interrupted while running docker command: " + args, e);
        }
    }
}
