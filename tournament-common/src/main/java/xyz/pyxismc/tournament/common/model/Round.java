package xyz.pyxismc.tournament.common.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import xyz.pyxismc.tournament.common.enums.RoundState;

/**
 * A round of a tournament: a set of groups, each played as a match.
 */
public record Round(
        UUID id,
        UUID tournamentId,
        int number,
        RoundState state,
        List<Group> groups
) {

    public Round {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tournamentId, "tournamentId");
        if (number < 1) {
            throw new IllegalArgumentException("number must be >= 1");
        }
        Objects.requireNonNull(state, "state");
        groups = List.copyOf(groups);
    }

    public Round withState(RoundState state) {
        return new Round(this.id, this.tournamentId, this.number, state, this.groups);
    }

    public Round withGroups(List<Group> groups) {
        return new Round(this.id, this.tournamentId, this.number, this.state, groups);
    }
}
