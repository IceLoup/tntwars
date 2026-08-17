package xyz.pyxismc.tournament.velocity.event;

import java.util.Objects;


import xyz.pyxismc.tournament.common.model.Match;

/**
 * Fired when a match starts (players transferred, server running).
 */
public record MatchStartedEvent(Match match) {

    public MatchStartedEvent {
        Objects.requireNonNull(match, "match");
    }
}
