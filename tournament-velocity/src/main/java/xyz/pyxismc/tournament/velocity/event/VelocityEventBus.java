package xyz.pyxismc.tournament.velocity.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.velocitypowered.api.proxy.ProxyServer;

/**
 * {@link TournamentEventBus} backed by the Velocity proxy event manager.
 */
public final class VelocityEventBus implements TournamentEventBus {

    private final ProxyServer proxy;
    private final Map<Class<?>, List<Consumer<?>>> internal = new ConcurrentHashMap<>();

    public VelocityEventBus(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Override
    public <T> void subscribe(Class<T> type, Consumer<T> consumer) {
        this.internal.computeIfAbsent(type, key -> new CopyOnWriteArrayList<>()).add(consumer);
    }

    @Override
    public void fire(Object event) {
        List<Consumer<?>> consumers = this.internal.get(event.getClass());
        if (consumers != null) {
            consumers.forEach(consumer -> ((Consumer<Object>) consumer).accept(event));
        }
        this.proxy.getEventManager().fire(event);
    }
}
