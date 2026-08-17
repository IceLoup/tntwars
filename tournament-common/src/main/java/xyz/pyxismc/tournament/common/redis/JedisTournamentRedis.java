package xyz.pyxismc.tournament.common.redis;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

/**
 * {@link TournamentRedis} backed by a Jedis pool. One dedicated connection
 * (checked out from the pool) per subscribed channel for the blocking
 * subscribe loop; the queue operations use pooled connections.
 */
public final class JedisTournamentRedis implements TournamentRedis {

    private static final int TIMEOUT_MS = 5000;
    private static final int MAX_TOTAL = 16;

    private final JedisPool pool;
    private final Map<String, Subscriber> subscriptions = new ConcurrentHashMap<>();

    public JedisTournamentRedis(String host, int port, String password) {
        String effectivePassword = (password == null || password.isEmpty()) ? null : password;
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(MAX_TOTAL);
        this.pool = new JedisPool(poolConfig, host, port, TIMEOUT_MS, effectivePassword);
        try (Jedis jedis = this.pool.getResource()) {
            jedis.ping();
        }
    }

    @Override
    public boolean isAvailable() {
        return !this.pool.isClosed();
    }

    @Override
    public void publish(String channel, String payload) {
        try (Jedis jedis = this.pool.getResource()) {
            jedis.publish(channel, payload);
        }
    }

    @Override
    public void subscribe(String channel, Consumer<String> listener) {
        this.subscriptions.computeIfAbsent(channel, ignored -> new Subscriber(channel, listener)).start();
    }

    @Override
    public void push(String key, String payload) {
        try (Jedis jedis = this.pool.getResource()) {
            jedis.lpush(key, payload);
        }
    }

    @Override
    public String pop(String key, int timeoutSeconds) {
        try (Jedis jedis = this.pool.getResource()) {
            List<String> popped = jedis.brpop(timeoutSeconds, key);
            return popped == null ? null : popped.get(1);
        }
    }

    @Override
    public void close() {
        this.subscriptions.values().forEach(Subscriber::stop);
        this.subscriptions.clear();
        this.pool.close();
    }

    /** One blocking subscription on a dedicated connection. */
    private final class Subscriber implements Runnable {

        private final String channel;
        private final Consumer<String> listener;
        private final Jedis jedis;
        private final AtomicBoolean started = new AtomicBoolean();

        private Subscriber(String channel, Consumer<String> listener) {
            this.channel = channel;
            this.listener = listener;
            this.jedis = JedisTournamentRedis.this.pool.getResource();
        }

        private void start() {
            if (!this.started.compareAndSet(false, true)) {
                return;
            }
            Thread thread = new Thread(this, "tournament-redis-sub-" + this.channel);
            thread.setDaemon(true);
            thread.start();
        }

        private void stop() {
            this.jedis.close();
        }

        @Override
        public void run() {
            JedisPubSub pubSub = new JedisPubSub() {
                @Override
                public void onMessage(String channel, String message) {
                    Subscriber.this.listener.accept(message);
                }
            };
            try {
                this.jedis.subscribe(pubSub, this.channel);
            } catch (RuntimeException ignored) {
                // connection closed on stop(), or server unreachable
            }
        }
    }
}