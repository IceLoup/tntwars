package xyz.pyxismc.tournament.velocity.round;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xyz.pyxismc.tournament.common.enums.MatchStatus;
import xyz.pyxismc.tournament.common.enums.RoundState;
import xyz.pyxismc.tournament.common.model.Match;
import xyz.pyxismc.tournament.common.model.Round;
import xyz.pyxismc.tournament.common.model.Team;
import xyz.pyxismc.tournament.common.model.TeamPlayer;
import xyz.pyxismc.tournament.common.result.MatchResult;
import xyz.pyxismc.tournament.common.result.Placement;
import xyz.pyxismc.tournament.common.result.TeamResult;
import xyz.pyxismc.tournament.velocity.event.MatchCreatedEvent;
import xyz.pyxismc.tournament.velocity.event.MatchFailedEvent;
import xyz.pyxismc.tournament.velocity.event.MatchFinishedEvent;
import xyz.pyxismc.tournament.velocity.event.MatchStartedEvent;
import xyz.pyxismc.tournament.velocity.event.RoundCreatedEvent;
import xyz.pyxismc.tournament.velocity.event.RoundFinishedEvent;
import xyz.pyxismc.tournament.velocity.event.RoundStartedEvent;
import xyz.pyxismc.tournament.velocity.testutil.FakeEventBus;

class RoundManagerTest {

    private static final int MAX = 3;
    private static final UUID TOURNAMENT_ID = UUID.randomUUID();

    private FakeEventBus eventBus;
    private RoundManager manager;

    @BeforeEach
    void setUp() {
        this.eventBus = new FakeEventBus();
        this.manager = new RoundManager(new DefaultRoundStrategy(), this.eventBus, MAX);
    }

    private static Team team(int index) {
        UUID id = UUID.randomUUID();
        return new Team(id, "Team " + index, id,
                List.of(new TeamPlayer(id, "player" + index, xyz.pyxismc.tournament.common.enums.TeamRole.CAPTAIN)),
                false);
    }

    private static List<Team> teams(int count) {
        return IntStream.range(0, count).mapToObj(RoundManagerTest::team).toList();
    }

    private static MatchResult resultOf(Match match, int winnerIndex) {
        List<Placement> order = List.of(Placement.WINNER, Placement.INTERMEDIATE, Placement.ELIMINATED);
        List<TeamResult> results = new java.util.ArrayList<>();
        int placementIndex = 0;
        for (int i = 0; i < match.teamIds().size(); i++) {
            Placement placement = i == winnerIndex ? Placement.WINNER : order.get(++placementIndex);
            results.add(new TeamResult(match.teamIds().get(i), placement));
        }
        return new MatchResult(match.id(), results, Map.of(), Map.of(),
                Duration.ofMinutes(5), Instant.now());
    }

    @Test
    void createFirstRoundBuildsGroupsAndMatches() {
        Round round = this.manager.createFirstRound(TOURNAMENT_ID, teams(6));

        assertEquals(1, round.number());
        assertEquals(RoundState.CREATED, round.state());
        assertEquals(2, round.groups().size());
        assertEquals(2, this.manager.getMatchesOfRound(round.id()).size());
        assertTrue(this.manager.getMatchesOfRound(round.id()).stream()
                .allMatch(match -> match.status() == MatchStatus.CREATED));
        assertEquals(1, this.eventBus.count(RoundCreatedEvent.class));
        assertEquals(2, this.eventBus.count(MatchCreatedEvent.class));
        assertTrue(this.manager.getCurrentRound().isPresent());
    }

    @Test
    void startRoundRunsAllMatches() {
        Round round = this.manager.createFirstRound(TOURNAMENT_ID, teams(6));

        Round running = this.manager.startRound(round.id());

        assertEquals(RoundState.RUNNING, running.state());
        assertTrue(this.manager.getMatchesOfRound(round.id()).stream()
                .allMatch(match -> match.status() == MatchStatus.RUNNING));
        assertEquals(1, this.eventBus.count(RoundStartedEvent.class));
        assertEquals(2, this.eventBus.count(MatchStartedEvent.class));
    }

    @Test
    void startRoundTwiceIsRejected() {
        Round round = this.manager.createFirstRound(TOURNAMENT_ID, teams(6));
        this.manager.startRound(round.id());
        assertThrows(RoundException.class, () -> this.manager.startRound(round.id()));
    }

    @Test
    void resultRequiresExactTeamSet() {
        Round round = this.manager.createFirstRound(TOURNAMENT_ID, teams(6));
        this.manager.startRound(round.id());
        Match match = this.manager.getMatchesOfRound(round.id()).getFirst();

        MatchResult wrong = new MatchResult(match.id(),
                match.teamIds().stream()
                        .map(teamId -> new TeamResult(teamId, Placement.WINNER))
                        .toList(),
                Map.of(), Map.of(), Duration.ofMinutes(5), Instant.now());
        assertThrows(RoundException.class, () -> this.manager.submitMatchResult(match.id(), wrong));

        MatchResult single = new MatchResult(match.id(),
                List.of(new TeamResult(match.teamIds().getFirst(), Placement.WINNER)),
                Map.of(), Map.of(), Duration.ofMinutes(5), Instant.now());
        assertThrows(RoundException.class, () -> this.manager.submitMatchResult(match.id(), single));
    }

    @Test
    void resultRequiresSingleWinnerAndDistinctPlacements() {
        Round round = this.manager.createFirstRound(TOURNAMENT_ID, teams(6));
        this.manager.startRound(round.id());
        Match match = this.manager.getMatchesOfRound(round.id()).getFirst();

        MatchResult twoWinners = new MatchResult(match.id(),
                match.teamIds().stream()
                        .map(teamId -> new TeamResult(teamId, Placement.WINNER))
                        .toList(),
                Map.of(), Map.of(), Duration.ofMinutes(5), Instant.now());
        assertThrows(RoundException.class, () -> this.manager.submitMatchResult(match.id(), twoWinners));

        MatchResult duplicated = new MatchResult(match.id(),
                List.of(new TeamResult(match.teamIds().get(0), Placement.WINNER),
                        new TeamResult(match.teamIds().get(1), Placement.ELIMINATED),
                        new TeamResult(match.teamIds().get(2), Placement.ELIMINATED)),
                Map.of(), Map.of(), Duration.ofMinutes(5), Instant.now());
        assertThrows(RoundException.class, () -> this.manager.submitMatchResult(match.id(), duplicated));
    }

    @Test
    void resultOnNonRunningMatchIsRejected() {
        Round round = this.manager.createFirstRound(TOURNAMENT_ID, teams(6));
        Match match = this.manager.getMatchesOfRound(round.id()).getFirst();

        assertThrows(RoundException.class, () -> this.manager.submitMatchResult(match.id(), resultOf(match, 0)));

        this.manager.startRound(round.id());
        this.manager.submitMatchResult(match.id(), resultOf(match, 0));
        assertThrows(RoundException.class, () -> this.manager.submitMatchResult(match.id(), resultOf(match, 1)));
    }

    @Test
    void roundFinishesAndNextRoundStartsAutomatically() {
        Round round = this.manager.createFirstRound(TOURNAMENT_ID, teams(6));
        this.manager.startRound(round.id());
        List<Match> matches = this.manager.getMatchesOfRound(round.id());

        this.manager.submitMatchResult(matches.get(0).id(), resultOf(matches.get(0), 0));
        assertEquals(RoundState.RUNNING, this.manager.getCurrentRound().orElseThrow().state());

        Optional<Round> after = this.manager.submitMatchResult(matches.get(1).id(), resultOf(matches.get(1), 1));

        assertEquals(1, this.eventBus.count(RoundFinishedEvent.class));
        Round next = after.orElseThrow();
        assertEquals(2, next.number());
        assertEquals(RoundState.RUNNING, next.state());
        assertEquals(1, this.manager.getMatchesOfRound(next.id()).size());
        assertEquals(2, this.eventBus.count(RoundCreatedEvent.class));
        assertEquals(2, this.eventBus.count(RoundStartedEvent.class));
    }

    @Test
    void tournamentEndsAfterFinalMatchAndChampionIsSet() {
        Round round = this.manager.createFirstRound(TOURNAMENT_ID, teams(6));
        this.manager.startRound(round.id());
        List<Match> matches = this.manager.getMatchesOfRound(round.id());
        this.manager.submitMatchResult(matches.get(0).id(), resultOf(matches.get(0), 0));
        Optional<Round> round2 = this.manager.submitMatchResult(matches.get(1).id(), resultOf(matches.get(1), 1));

        Match finalMatch = this.manager.getMatchesOfRound(round2.orElseThrow().id()).getFirst();
        Optional<Round> afterFinal = this.manager.submitMatchResult(finalMatch.id(), resultOf(finalMatch, 0));

        assertTrue(afterFinal.isEmpty());
        assertTrue(this.manager.isFinished());
        assertTrue(this.manager.getCurrentRound().isEmpty());
        assertEquals(matches.get(0).teamIds().get(0), this.manager.getChampionTeamId().orElseThrow());
        assertEquals(2, this.eventBus.count(RoundFinishedEvent.class));
    }

    @Test
    void failMatchMarksFailedAndFiresEvent() {
        Round round = this.manager.createFirstRound(TOURNAMENT_ID, teams(6));
        this.manager.startRound(round.id());
        Match match = this.manager.getMatchesOfRound(round.id()).getFirst();

        this.manager.failMatch(match.id(), "Server crashed");

        assertEquals(MatchStatus.FAILED, this.manager.getMatch(match.id()).orElseThrow().status());
        assertEquals(1, this.eventBus.count(MatchFailedEvent.class));
        assertThrows(RoundException.class,
                () -> this.manager.submitMatchResult(match.id(), resultOf(match, 0)));
        assertThrows(RoundException.class, () -> this.manager.failMatch(match.id(), "again"));
    }

    @Test
    void cancelAllCancelsActiveMatches() {
        Round round = this.manager.createFirstRound(TOURNAMENT_ID, teams(6));
        this.manager.startRound(round.id());
        List<Match> matches = this.manager.getMatchesOfRound(round.id());
        this.manager.submitMatchResult(matches.get(0).id(), resultOf(matches.get(0), 0));

        this.manager.cancelAll();

        List<MatchStatus> statuses = this.manager.getMatches().stream().map(Match::status).toList();
        assertTrue(statuses.contains(MatchStatus.FINISHED));
        assertFalse(statuses.contains(MatchStatus.RUNNING));
        assertFalse(statuses.contains(MatchStatus.CREATED));
        assertTrue(this.manager.isFinished());
    }
}