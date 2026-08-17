package xyz.pyxismc.tournament.common.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import xyz.pyxismc.tournament.common.enums.MatchStatus;

/**
 * A match: up to {@code max-teams-per-match} teams competing on one server.
 *
 * <p>The server is identified by its unique {@code serverId} (e.g.
 * {@code tournament-match-<uuid>}) but the match must always be traced by
 * its UUID, never by the server name alone.</p>
 */
public record Match(
        UUID id,
        UUID roundId,
        List<UUID> teamIds,
        String serverId,
        MatchStatus status
) {

    public Match {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(roundId, "roundId");
        teamIds = List.copyOf(teamIds);
        Objects.requireNonNull(status, "status");
    }

    public Match withTeamIds(List<UUID> teamIds) {
        return new Match(this.id, this.roundId, teamIds, this.serverId, this.status);
    }

    public Match withServerId(String serverId) {
        return new Match(this.id, this.roundId, this.teamIds, serverId, this.status);
    }

    public Match withStatus(MatchStatus status) {
        return new Match(this.id, this.roundId, this.teamIds, this.serverId, status);
    }
}
