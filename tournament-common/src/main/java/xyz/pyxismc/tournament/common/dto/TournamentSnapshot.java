package xyz.pyxismc.tournament.common.dto;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import xyz.pyxismc.tournament.common.model.Match;
import xyz.pyxismc.tournament.common.model.Round;
import xyz.pyxismc.tournament.common.model.Team;
import xyz.pyxismc.tournament.common.model.Tournament;
import xyz.pyxismc.tournament.common.result.MatchResult;

/**
 * Full immutable snapshot of a (finished or cancelled) tournament: the
 * tournament itself, the registered teams, all rounds and matches, every
 * match result (with team and player stats) and the champion team.
 */
public record TournamentSnapshot(
        Tournament tournament,
        List<Team> teams,
        List<Round> rounds,
        List<Match> matches,
        Map<UUID, MatchResult> results,
        UUID championTeamId
) {

    public TournamentSnapshot {
        Objects.requireNonNull(tournament, "tournament");
        teams = List.copyOf(teams);
        rounds = List.copyOf(rounds);
        matches = List.copyOf(matches);
        results = Map.copyOf(results);
    }

    public Optional<Team> teamOf(UUID teamId) {
        return this.teams.stream().filter(team -> team.id().equals(teamId)).findFirst();
    }

    public List<Match> matchesOfRound(UUID roundId) {
        return this.matches.stream()
                .filter(match -> match.roundId().equals(roundId))
                .toList();
    }
}
