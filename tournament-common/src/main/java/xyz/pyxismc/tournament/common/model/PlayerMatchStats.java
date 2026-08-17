package xyz.pyxismc.tournament.common.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Statistics of one player for a single match.
 *
 * <p>Extensible: healing, blocks, objectives, MVP, etc. can be added later.</p>
 */
public record PlayerMatchStats(
        UUID playerId,
        UUID teamId,
        long kills,
        long deaths,
        long assists,
        long damage
) {

    public PlayerMatchStats {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(teamId, "teamId");
        if (kills < 0) throw new IllegalArgumentException("kills must be >= 0");
        if (deaths < 0) throw new IllegalArgumentException("deaths must be >= 0");
        if (assists < 0) throw new IllegalArgumentException("assists must be >= 0");
        if (damage < 0) throw new IllegalArgumentException("damage must be >= 0");
    }
}
