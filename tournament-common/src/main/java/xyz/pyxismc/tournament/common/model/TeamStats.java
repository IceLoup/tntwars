package xyz.pyxismc.tournament.common.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Aggregated statistics of a team across the tournament.
 *
 * <p>{@code wins} counts WINNER placements, {@code intermediate} counts
 * INTERMEDIATE placements and {@code eliminations} counts ELIMINATED
 * placements.</p>
 */
public record TeamStats(
        UUID teamId,
        long matches,
        long wins,
        long intermediate,
        long eliminations,
        long kills,
        long deaths
) {

    public TeamStats {
        Objects.requireNonNull(teamId, "teamId");
        requireNonNegative(matches, "matches");
        requireNonNegative(wins, "wins");
        requireNonNegative(intermediate, "intermediate");
        requireNonNegative(eliminations, "eliminations");
        requireNonNegative(kills, "kills");
        requireNonNegative(deaths, "deaths");
    }

    public static TeamStats empty(UUID teamId) {
        return new TeamStats(teamId, 0, 0, 0, 0, 0, 0);
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0");
        }
    }
}
