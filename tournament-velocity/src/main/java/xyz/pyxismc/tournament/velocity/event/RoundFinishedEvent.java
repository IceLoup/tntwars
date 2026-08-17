package xyz.pyxismc.tournament.velocity.event;

import java.util.Objects;


import xyz.pyxismc.tournament.common.model.Round;

/**
 * Fired when a round finishes (all its matches reported results).
 */
public record RoundFinishedEvent(Round round) {

    public RoundFinishedEvent {
        Objects.requireNonNull(round, "round");
    }
}
