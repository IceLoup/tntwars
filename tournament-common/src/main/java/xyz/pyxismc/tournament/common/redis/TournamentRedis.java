package xyz.pyxismc.tournament.common.redis;

import java.util.function.Consumer;

/**
 * Minimal Redis façade used by the tournament protocol. Kept abstract so
 * unit tests can run against an in-memory implementation and the real
 * deployment against {@link JedisTournamentRedis}.
 */
public interface TournamentRedis {

    /** True while the connection pool is open. */
    boolean isAvailable();

    /** Publishes a payload on a Pub/Sub channel. */
    void publish(String channel, String payload);

    /** Registers a listener for a channel (idempotent per channel). */
    void subscribe(String channel, Consumer<String> listener);

    /** Left-pushes a payload onto a list (provisioning queue). */
    void push(String key, String payload);

    /**
     * Blocking right-pop with a timeout. Returns the payload, or null when
     * the timeout elapsed without any item.
     */
    String pop(String key, int timeoutSeconds);

    /** Closes the pool and every subscription. */
    void close();
}