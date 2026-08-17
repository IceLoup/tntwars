package xyz.pyxismc.tournament.velocity.event;

import java.util.Objects;


import xyz.pyxismc.tournament.common.model.Round;

/**
 * Fired when a round is created (groups built).
 */
public record RoundCreatedEvent(Round round) {

    public RoundCreatedEvent {
        Objects.requireNonNull(round, "round");
    }
}
