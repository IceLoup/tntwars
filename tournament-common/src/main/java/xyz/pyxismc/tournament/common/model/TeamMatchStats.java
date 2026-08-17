package xyz.pyxismc.tournament.common.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Statistics of one team for a single match.
 */
public record TeamMatchStats(
        UUID teamId,
        long kills,
        long deaths
) {

    public TeamMatchStats {
        Objects.requireNonNull(teamId, "teamId");
        if (kills < 0) throw new IllegalArgumentException("kills must be >= 0");
        if (deaths < 0) throw new IllegalArgumentException("deaths must be >= 0");
    }
}
