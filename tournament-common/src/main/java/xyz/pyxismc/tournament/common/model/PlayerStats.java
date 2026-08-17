package xyz.pyxismc.tournament.common.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Aggregated (career) statistics of a player across the tournament.
 */
public record PlayerStats(
        UUID playerId,
        long matches,
        long wins,
        long kills,
        long deaths,
        long assists,
        long damage
) {

    public PlayerStats {
        Objects.requireNonNull(playerId, "playerId");
        requireNonNegative(matches, "matches");
        requireNonNegative(wins, "wins");
        requireNonNegative(kills, "kills");
        requireNonNegative(deaths, "deaths");
        requireNonNegative(assists, "assists");
        requireNonNegative(damage, "damage");
    }

    public static PlayerStats empty(UUID playerId) {
        return new PlayerStats(playerId, 0, 0, 0, 0, 0, 0);
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0");
        }
    }
}
