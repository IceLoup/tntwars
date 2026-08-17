package xyz.pyxismc.tournament.common.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import xyz.pyxismc.tournament.common.enums.TournamentState;

/**
 * A tournament. References are stored as UUIDs so the model stays
 * database-friendly; managers resolve the referenced objects.
 */
public record Tournament(
        UUID id,
        String name,
        TournamentState state,
        List<UUID> teamIds,
        List<UUID> roundIds,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {

    public Tournament {
        Objects.requireNonNull(id, "id");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(state, "state");
        teamIds = List.copyOf(teamIds);
        roundIds = List.copyOf(roundIds);
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public Tournament withState(TournamentState state) {
        return new Tournament(
                this.id, this.name, state, this.teamIds, this.roundIds,
                this.createdAt, this.startedAt, this.finishedAt);
    }

    public Tournament withTeamIds(List<UUID> teamIds) {
        return new Tournament(
                this.id, this.name, this.state, teamIds, this.roundIds,
                this.createdAt, this.startedAt, this.finishedAt);
    }

    public Tournament withRoundIds(List<UUID> roundIds) {
        return new Tournament(
                this.id, this.name, this.state, this.teamIds, roundIds,
                this.createdAt, this.startedAt, this.finishedAt);
    }

    public Tournament withStartedAt(Instant startedAt) {
        return new Tournament(
                this.id, this.name, this.state, this.teamIds, this.roundIds,
                this.createdAt, startedAt, this.finishedAt);
    }

    public Tournament withFinishedAt(Instant finishedAt) {
        return new Tournament(
                this.id, this.name, this.state, this.teamIds, this.roundIds,
                this.createdAt, this.startedAt, finishedAt);
    }
}
