package xyz.pyxismc.tournament.common.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A group (pool) of up to {@code max-teams-per-match} teams within a round.
 */
public record Group(
        UUID id,
        UUID roundId,
        String name,
        List<UUID> teamIds
) {

    public Group {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(roundId, "roundId");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        teamIds = List.copyOf(teamIds);
    }

    public Group withName(String name) {
        return new Group(this.id, this.roundId, name, this.teamIds);
    }

    public Group withTeamIds(List<UUID> teamIds) {
        return new Group(this.id, this.roundId, this.name, teamIds);
    }
}
