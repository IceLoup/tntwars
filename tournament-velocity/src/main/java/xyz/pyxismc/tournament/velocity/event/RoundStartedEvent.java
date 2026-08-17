package xyz.pyxismc.tournament.velocity.event;

import java.util.Objects;


import xyz.pyxismc.tournament.common.model.Round;

/**
 * Fired when a round starts (its matches are launched).
 */
public record RoundStartedEvent(Round round) {

    public RoundStartedEvent {
        Objects.requireNonNull(round, "round");
    }
}
