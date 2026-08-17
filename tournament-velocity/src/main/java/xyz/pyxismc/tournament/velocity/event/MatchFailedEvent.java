package xyz.pyxismc.tournament.velocity.event;

import java.util.Objects;


import xyz.pyxismc.tournament.common.model.Match;

/**
 * Fired when a match failed (server crash, timeout, invalid result) and
 * cannot be resumed.
 */
public record MatchFailedEvent(Match match, String reason) {

    public MatchFailedEvent {
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(reason, "reason");
    }
}
