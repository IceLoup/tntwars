package xyz.pyxismc.tournament.common.message;

import java.util.Objects;
import java.util.UUID;

import xyz.pyxismc.tournament.common.result.MatchResult;

/**
 * Final match outcome sent by the Paper match server back to Velocity.
 * Velocity re-validates everything; Paper never decides progression.
 */
public record MatchResultMessage(UUID matchId, String serverId, MatchResult result) {

    public MatchResultMessage {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(result, "result");
        if (!matchId.equals(result.matchId())) {
            throw new IllegalArgumentException("result match id does not match the message match id");
        }
    }
}