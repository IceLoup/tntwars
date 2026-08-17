package xyz.pyxismc.tournament.velocity.docker;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Environment values injected into every match container: how to reach Redis
 * and which server id the Paper plugin must use.
 */
public record DockerEnvironment(String redisHost, int redisPort, String redisPassword) {

    public DockerEnvironment {
        Objects.requireNonNull(redisHost, "redisHost");
        Objects.requireNonNull(redisPassword, "redisPassword");
    }

    /** Builds the {@code --env KEY=value} CLI arguments for a given server id. */
    public List<String> envArgs(String serverId) {
        List<String> args = new ArrayList<>();
        args.add("--env");
        args.add("TOURNAMENT_SERVER_ID=" + serverId);
        args.add("--env");
        args.add("REDIS_HOST=" + this.redisHost);
        args.add("--env");
        args.add("REDIS_PORT=" + this.redisPort);
        if (!this.redisPassword.isEmpty()) {
            args.add("--env");
            args.add("REDIS_PASSWORD=" + this.redisPassword);
        }
        return args;
    }
}
