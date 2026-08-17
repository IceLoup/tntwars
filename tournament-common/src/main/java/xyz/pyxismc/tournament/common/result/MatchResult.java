package xyz.pyxismc.tournament.common.result;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import xyz.pyxismc.tournament.common.model.PlayerMatchStats;
import xyz.pyxismc.tournament.common.model.TeamMatchStats;

/**
 * Final result of a match, as reported by the Paper server.
 *
 * <p>Data received from Paper is untrusted: Velocity must validate it before
 * accepting it (match exists, match running, server matches the match, teams
 * belong to the match, no team submitted twice, exact team set).</p>
 */
public record MatchResult(
        UUID matchId,
        List<TeamResult> results,
        Map<UUID, TeamMatchStats> teamStats,
        Map<UUID, PlayerMatchStats> playerStats,
        Duration duration,
        Instant finishedAt
) {

    public MatchResult {
        Objects.requireNonNull(matchId, "matchId");
        results = List.copyOf(results);
        teamStats = Map.copyOf(teamStats);
        playerStats = Map.copyOf(playerStats);
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(finishedAt, "finishedAt");
    }

    public Optional<TeamResult> resultOf(UUID teamId) {
        return this.results.stream()
                .filter(result -> result.teamId().equals(teamId))
                .findFirst();
    }

    public boolean hasResultFor(UUID teamId) {
        return resultOf(teamId).isPresent();
    }

    /**
     * True if the results cover exactly the expected teams: same size, every
     * expected team present and no duplicate team.
     */
    public boolean containsExactlyTeams(Collection<UUID> expectedTeamIds) {
        if (this.results.size() != expectedTeamIds.size()) {
            return false;
        }
        Set<UUID> actual = this.results.stream()
                .map(TeamResult::teamId)
                .collect(Collectors.toSet());
        return actual.size() == this.results.size()
                && actual.containsAll(expectedTeamIds);
    }
}
