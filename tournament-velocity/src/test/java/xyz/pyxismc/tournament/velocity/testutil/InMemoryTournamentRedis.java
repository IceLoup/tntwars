package xyz.pyxismc.tournament.velocity.testutil;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import xyz.pyxismc.tournament.common.redis.TournamentRedis;

/**
 * In-memory {@link TournamentRedis} for tests: delivers publishes to every
 * registered listener (same-JVM, like a real Redis Pub/Sub) and keeps each
 * queue in a blocking queue.
 */
public final class InMemoryTournamentRedis implements TournamentRedis {

    private final Map<String, List<Consumer<String>>> listeners = new ConcurrentHashMap<>();
    private final Map<String, BlockingQueue<String>> queues = new ConcurrentHashMap<>();
    private final List<String> recordedPublishes = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    @Override
    public boolean isAvailable() {
        return !this.closed;
    }

    @Override
    public void publish(String channel, String payload) {
        this.recordedPublishes.add(channel + "\u0000" + payload);
        for (Consumer<String> listener : this.listeners.getOrDefault(channel, List.of())) {
            listener.accept(payload);
        }
    }

    /** Every publish as {@code channel\0payload}, in order. */
    public List<String> recordedPublishes() {
        return List.copyOf(this.recordedPublishes);
    }

    @Override
    public void subscribe(String channel, Consumer<String> listener) {
        this.listeners.computeIfAbsent(channel, ignored -> new CopyOnWriteArrayList<>()).add(listener);
    }

    @Override
    public void push(String key, String payload) {
        this.queues.computeIfAbsent(key, ignored -> new LinkedBlockingQueue<>()).add(payload);
    }

    @Override
    public String pop(String key, int timeoutSeconds) {
        BlockingQueue<String> queue = this.queues.computeIfAbsent(key, ignored -> new LinkedBlockingQueue<>());
        try {
            return queue.poll(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public void close() {
        this.closed = true;
        this.listeners.clear();
    }
}