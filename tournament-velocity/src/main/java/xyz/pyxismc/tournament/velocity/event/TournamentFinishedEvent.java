package xyz.pyxismc.tournament.velocity.event;

import java.util.Objects;


import xyz.pyxismc.tournament.common.model.Tournament;

/**
 * Fired when a tournament reaches the FINISHED state.
 */
public record TournamentFinishedEvent(Tournament tournament) {

    public TournamentFinishedEvent {
        Objects.requireNonNull(tournament, "tournament");
    }
}
