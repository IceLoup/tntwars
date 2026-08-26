package xyz.pyxismc.tournament.common.message;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Published by Velocity to {@code tournament:match:<serverId>} once the
 * server is provisioned. The Paper plugin on that server starts the match.
 *
 * @param playersByTeam the player uuids of every team, keyed by team id
 */
public record MatchStartMessage(
        UUID matchId,
        String serverId,
        String tournamentName,
        List<UUID> teamIds,
        Map<UUID, String> teamNames,
        Map<UUID, List<UUID>> playersByTeam,
        int playersPerTeam
) {

    public MatchStartMessage {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(tournamentName, "tournamentName");
        teamIds = List.copyOf(teamIds);
        teamNames = Map.copyOf(teamNames);
        playersByTeam = Map.copyOf(playersByTeam);
        if (playersByTeam.keySet().size() != teamIds.size()
                || !playersByTeam.keySet().containsAll(teamIds)) {
            throw new IllegalArgumentException("playersByTeam must map exactly the team ids");
        }
        if (!teamNames.keySet().containsAll(teamIds)) {
            throw new IllegalArgumentException("teamNames must contain every team id");
        }
        if (playersByTeam.values().stream().anyMatch(List::isEmpty)) {
            throw new IllegalArgumentException("every team must have at least one player");
        }
        if (playersPerTeam < 1) {
            throw new IllegalArgumentException("playersPerTeam must be >= 1");
        }
    }
}