package xyz.pyxismc.tournament.velocity.event;

import java.util.Objects;


import xyz.pyxismc.tournament.common.model.Match;

/**
 * Fired when a match is created.
 */
public record MatchCreatedEvent(Match match) {

    public MatchCreatedEvent {
        Objects.requireNonNull(match, "match");
    }
}
