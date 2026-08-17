package xyz.pyxismc.tournament.common.message;

import java.util.Objects;
import java.util.UUID;

/**
 * Acknowledgment sent by the Paper match server back to Velocity once the
 * match is loaded and ready to play.
 */
public record MatchReadyMessage(UUID matchId, String serverId) {

    public MatchReadyMessage {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(serverId, "serverId");
    }
}