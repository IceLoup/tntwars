package xyz.pyxismc.tournament.velocity.event;

import java.util.Objects;


import xyz.pyxismc.tournament.common.model.Tournament;

/**
 * Fired when a tournament starts: teams are locked, rounds are being built.
 */
public record TournamentStartedEvent(Tournament tournament) {

    public TournamentStartedEvent {
        Objects.requireNonNull(tournament, "tournament");
    }
}
