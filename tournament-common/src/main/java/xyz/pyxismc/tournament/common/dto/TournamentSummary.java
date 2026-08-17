package xyz.pyxismc.tournament.common.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import xyz.pyxismc.tournament.common.enums.TournamentState;

/**
 * Lightweight summary of a stored tournament, used for history listings.
 */
public record TournamentSummary(
        UUID id,
        String name,
        TournamentState state,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        int teamCount,
        UUID championTeamId
) {

    public TournamentSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        if (teamCount < 0) {
            throw new IllegalArgumentException("teamCount must be >= 0");
        }
    }
}
