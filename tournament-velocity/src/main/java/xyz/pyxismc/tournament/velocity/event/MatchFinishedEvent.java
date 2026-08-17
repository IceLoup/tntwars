package xyz.pyxismc.tournament.velocity.event;

import java.util.Objects;


import xyz.pyxismc.tournament.common.model.Match;
import xyz.pyxismc.tournament.common.result.MatchResult;

/**
 * Fired when a match finished and its result was validated and stored.
 */
public record MatchFinishedEvent(Match match, MatchResult result) {

    public MatchFinishedEvent {
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(result, "result");
    }
}
