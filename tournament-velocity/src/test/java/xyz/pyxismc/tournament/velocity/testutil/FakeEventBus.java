package xyz.pyxismc.tournament.velocity.testutil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import xyz.pyxismc.tournament.velocity.event.TournamentEventBus;

/**
 * In-memory {@link TournamentEventBus} for tests: records fired events and
 * dispatches to internal subscribers.
 */
public final class FakeEventBus implements TournamentEventBus {

    private final Map<Class<?>, List<Consumer<?>>> subscribers = new HashMap<>();
    private final List<Object> fired = new ArrayList<>();

    @Override
    public <T> void subscribe(Class<T> type, Consumer<T> consumer) {
        this.subscribers.computeIfAbsent(type, key -> new ArrayList<>()).add(consumer);
    }

    @Override
    public void fire(Object event) {
        this.fired.add(event);
        List<Consumer<?>> consumers = this.subscribers.get(event.getClass());
        if (consumers != null) {
            consumers.forEach(consumer -> ((Consumer<Object>) consumer).accept(event));
        }
    }

    /** Immutable snapshot of all fired events. */
    public List<Object> firedEvents() {
        return List.copyOf(this.fired);
    }

    public long count(Class<?> type) {
        return this.fired.stream().filter(type::isInstance).count();
    }

    public void reset() {
        this.fired.clear();
        this.subscribers.clear();
    }
}
