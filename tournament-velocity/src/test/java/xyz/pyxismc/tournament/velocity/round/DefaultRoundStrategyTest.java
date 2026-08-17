package xyz.pyxismc.tournament.velocity.round;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import xyz.pyxismc.tournament.common.model.Group;
import xyz.pyxismc.tournament.common.model.Team;
import xyz.pyxismc.tournament.common.model.TeamPlayer;
import xyz.pyxismc.tournament.common.result.MatchResult;
import xyz.pyxismc.tournament.common.result.Placement;
import xyz.pyxismc.tournament.common.result.TeamResult;

class DefaultRoundStrategyTest {

    private static final int MAX = 3;

    private static Team team(int index) {
        UUID id = UUID.randomUUID();
        return new Team(id, "Team " + index, id,
                List.of(new TeamPlayer(id, "player" + index, xyz.pyxismc.tournament.common.enums.TeamRole.CAPTAIN)),
                false);
    }

    private static Team namedTeam(String name) {
        UUID id = UUID.randomUUID();
        return new Team(id, name, id,
                List.of(new TeamPlayer(id, "player", xyz.pyxismc.tournament.common.enums.TeamRole.CAPTAIN)),
                false);
    }

    private static List<Team> teams(int count) {
        return IntStream.range(0, count).mapToObj(DefaultRoundStrategyTest::team).toList();
    }

    private static MatchResult resultOf(UUID matchId, List<UUID> teamIds, int winnerIndex) {
        List<TeamResult> results = teamIds.stream()
                .map(teamId -> new TeamResult(teamId,
                        teamId.equals(teamIds.get(winnerIndex)) ? Placement.WINNER : Placement.ELIMINATED))
                .toList();
        return new MatchResult(matchId, results, java.util.Map.of(), java.util.Map.of(),
                java.time.Duration.ofMinutes(5), java.time.Instant.now());
    }

    @Test
    void firstRoundChunksIntoGroupsOfThree() {
        DefaultRoundStrategy strategy = new DefaultRoundStrategy();
        List<Group> groups = strategy.buildFirstRound(UUID.randomUUID(), teams(24), MAX);

        assertEquals(8, groups.size());
        assertTrue(groups.stream().allMatch(group -> group.teamIds().size() == 3));
        assertEquals("A", groups.get(0).name());
        assertEquals("H", groups.get(7).name());
    }

    @Test
    void firstRoundSortsTeamsByNameDeterministically() {
        DefaultRoundStrategy strategy = new DefaultRoundStrategy();
        Team zebra = namedTeam("Zebra");
        Team alpha = namedTeam("Alpha");
        Team bravo = namedTeam("Bravo");
        List<Group> groups = strategy.buildFirstRound(UUID.randomUUID(),
                List.of(zebra, alpha, bravo), MAX);

        assertEquals(alpha.id(), groups.get(0).teamIds().get(0));
        assertEquals(bravo.id(), groups.get(0).teamIds().get(1));
        assertEquals(zebra.id(), groups.get(0).teamIds().get(2));
    }

    @Test
    void firstRoundRebalancesLeftoverIntoMatchesOfTwo() {
        DefaultRoundStrategy strategy = new DefaultRoundStrategy();
        List<Group> groups = strategy.buildFirstRound(UUID.randomUUID(), teams(16), MAX);

        assertEquals(6, groups.size());
        assertEquals(List.of(3, 3, 3, 3, 2, 2), groups.stream().map(group -> group.teamIds().size()).toList());
    }

    @Test
    void firstRoundRejectsLessThanTwoTeams() {
        DefaultRoundStrategy strategy = new DefaultRoundStrategy();
        assertThrows(RoundException.class, () -> strategy.buildFirstRound(UUID.randomUUID(), teams(1), MAX));
    }

    @Test
    void firstRoundWithMaxTwoRejectsOddTeamCount() {
        DefaultRoundStrategy strategy = new DefaultRoundStrategy();
        assertThrows(RoundException.class,
                () -> strategy.buildFirstRound(UUID.randomUUID(), teams(5), 2));
    }

    @Test
    void nextRoundAdvancesWinnersInMatchOrder() {
        DefaultRoundStrategy strategy = new DefaultRoundStrategy();
        UUID roundId = UUID.randomUUID();
        List<Group> groups = strategy.buildFirstRound(roundId, teams(6), MAX);
        java.util.Map<UUID, MatchResult> results = new java.util.HashMap<>();
        List<UUID> matchIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        results.put(matchIds.get(0), resultOf(matchIds.get(0), groups.get(0).teamIds(), 0));
        results.put(matchIds.get(1), resultOf(matchIds.get(1), groups.get(1).teamIds(), 1));

        List<Group> next = strategy.buildNextRound(UUID.randomUUID(), 2,
                List.of(new xyz.pyxismc.tournament.common.model.Match(matchIds.get(0), roundId,
                        groups.get(0).teamIds(), null, xyz.pyxismc.tournament.common.enums.MatchStatus.FINISHED),
                        new xyz.pyxismc.tournament.common.model.Match(matchIds.get(1), roundId,
                                groups.get(1).teamIds(), null, xyz.pyxismc.tournament.common.enums.MatchStatus.FINISHED)),
                results, MAX);

        assertEquals(1, next.size());
        assertEquals(2, next.get(0).teamIds().size());
        assertEquals(groups.get(0).teamIds().get(0), next.get(0).teamIds().get(0));
        assertEquals(groups.get(1).teamIds().get(1), next.get(0).teamIds().get(1));
    }

    @Test
    void nextRoundEndsWhenFewerThanTwoRemain() {
        DefaultRoundStrategy strategy = new DefaultRoundStrategy();
        UUID roundId = UUID.randomUUID();
        List<Group> groups = strategy.buildFirstRound(roundId, teams(3), MAX);
        UUID matchId = UUID.randomUUID();
        java.util.Map<UUID, MatchResult> results = java.util.Map.of(matchId,
                resultOf(matchId, groups.get(0).teamIds(), 0));

        List<Group> next = strategy.buildNextRound(UUID.randomUUID(), 2,
                List.of(new xyz.pyxismc.tournament.common.model.Match(matchId, roundId,
                        groups.get(0).teamIds(), null, xyz.pyxismc.tournament.common.enums.MatchStatus.FINISHED)),
                results, MAX);

        assertTrue(next.isEmpty());
    }

    @Test
    void nextRoundSplitsFourWinnersIntoTwoTwoTeamMatches() {
        DefaultRoundStrategy strategy = new DefaultRoundStrategy();
        UUID roundId = UUID.randomUUID();
        List<Group> groups = strategy.buildFirstRound(roundId, teams(12), MAX);
        java.util.Map<UUID, MatchResult> results = new java.util.HashMap<>();
        List<xyz.pyxismc.tournament.common.model.Match> matches = new java.util.ArrayList<>();
        for (Group group : groups) {
            UUID matchId = UUID.randomUUID();
            results.put(matchId, resultOf(matchId, group.teamIds(), 0));
            matches.add(new xyz.pyxismc.tournament.common.model.Match(matchId, roundId,
                    group.teamIds(), null, xyz.pyxismc.tournament.common.enums.MatchStatus.FINISHED));
        }

        List<Group> next = strategy.buildNextRound(UUID.randomUUID(), 2, matches, results, MAX);

        assertEquals(2, next.size());
        assertEquals(List.of(2, 2), next.stream().map(group -> group.teamIds().size()).toList());
        assertTrue(next.stream().flatMap(group -> group.teamIds().stream()).distinct().count() == 4);
    }

    @Test
    void nextRoundRebalancesSevenWinnersIntoThreeTwoTwo() {
        DefaultRoundStrategy strategy = new DefaultRoundStrategy();
        UUID roundId = UUID.randomUUID();
        List<Group> groups = strategy.buildFirstRound(roundId, teams(21), MAX);
        java.util.Map<UUID, MatchResult> results = new java.util.HashMap<>();
        List<xyz.pyxismc.tournament.common.model.Match> matches = new java.util.ArrayList<>();
        for (Group group : groups) {
            UUID matchId = UUID.randomUUID();
            results.put(matchId, resultOf(matchId, group.teamIds(), 0));
            matches.add(new xyz.pyxismc.tournament.common.model.Match(matchId, roundId,
                    group.teamIds(), null, xyz.pyxismc.tournament.common.enums.MatchStatus.FINISHED));
        }

        List<Group> next = strategy.buildNextRound(UUID.randomUUID(), 2, matches, results, MAX);

        assertEquals(3, next.size());
        assertEquals(List.of(3, 2, 2), next.stream().map(group -> group.teamIds().size()).toList());
    }
}