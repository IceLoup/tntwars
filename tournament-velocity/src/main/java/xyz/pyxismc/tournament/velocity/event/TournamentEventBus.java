package xyz.pyxismc.tournament.velocity.event;

import java.util.function.Consumer;

/**
 * Internal event bus used by the managers. Events are dispatched to internal
 * subscribers and, on Velocity, forwarded to the proxy event manager so
 * other plugins can listen too.
 */
public interface TournamentEventBus {

    /** Subscribes a consumer to events of the given type (internal dispatch). */
    <T> void subscribe(Class<T> type, Consumer<T> consumer);

    /** Fires an event to internal subscribers and the proxy event manager. */
    void fire(Object event);
}
