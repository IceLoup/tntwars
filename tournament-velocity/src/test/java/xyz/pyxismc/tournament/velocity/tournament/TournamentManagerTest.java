package xyz.pyxismc.tournament.velocity.tournament;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xyz.pyxismc.tournament.common.enums.MatchStatus;
import xyz.pyxismc.tournament.common.enums.RoundState;
import xyz.pyxismc.tournament.common.enums.TournamentState;
import xyz.pyxismc.tournament.common.model.Match;
import xyz.pyxismc.tournament.common.model.Player;
import xyz.pyxismc.tournament.common.model.Round;
import xyz.pyxismc.tournament.common.model.Team;
import xyz.pyxismc.tournament.common.model.Tournament;
import xyz.pyxismc.tournament.common.result.MatchResult;
import xyz.pyxismc.tournament.common.result.Placement;
import xyz.pyxismc.tournament.common.result.TeamResult;
import xyz.pyxismc.tournament.velocity.config.TournamentConfig;
import xyz.pyxismc.tournament.velocity.event.MatchFinishedEvent;
import xyz.pyxismc.tournament.velocity.event.RoundCreatedEvent;
import xyz.pyxismc.tournament.velocity.event.RoundStartedEvent;
import xyz.pyxismc.tournament.velocity.event.TournamentFinishedEvent;
import xyz.pyxismc.tournament.velocity.event.TournamentStartedEvent;
import xyz.pyxismc.tournament.velocity.round.DefaultRoundStrategy;
import xyz.pyxismc.tournament.velocity.round.RoundManager;
import xyz.pyxismc.tournament.velocity.team.TeamManager;
import xyz.pyxismc.tournament.velocity.testutil.FakeEventBus;

class TournamentManagerTest {

    private FakeEventBus eventBus;
    private TeamManager teamManager;
    private RoundManager roundManager;
    private TournamentManager manager;

    @BeforeEach
    void setUp() {
        this.eventBus = new FakeEventBus();
        this.teamManager = new TeamManager(TournamentConfig.defaults(), this.eventBus);
        this.roundManager = new RoundManager(
                new DefaultRoundStrategy(),
                this.eventBus,
                TournamentConfig.defaults().tournament().maxTeamsPerMatch());
        this.manager = new TournamentManager(
                TournamentConfig.defaults(), this.teamManager, this.roundManager, this.eventBus);
    }

    /** Registers 3 complete teams of 3 players. */
    private List<Team> registerThreeFullTeams() {
        return List.of(
                fullTeam("Alpha", "a1", "a2", "a3"),
                fullTeam("Beta", "b1", "b2", "b3"),
                fullTeam("Gamma", "c1", "c2", "c3"));
    }

    private Team fullTeam(String teamName, String... players) {
        Player captain = player(players[0]);
        Team team = this.teamManager.createTeam(captain, teamName);
        for (int i = 1; i < players.length; i++) {
            Player member = player(players[i]);
            this.teamManager.invitePlayer(captain.uuid(), member.uuid());
            this.teamManager.acceptInvite(member, team.id());
        }
        return team;
    }

    private static Player player(String name) {
        return new Player(UUID.randomUUID(), name);
    }

    /** Registers 6 complete teams: 2 groups of 3 in round 1. */
    private List<Team> registerSixFullTeams() {
        return List.of(
                fullTeam("Alpha", "a1", "a2", "a3"),
                fullTeam("Beta", "b1", "b2", "b3"),
                fullTeam("Gamma", "c1", "c2", "c3"),
                fullTeam("Delta", "d1", "d2", "d3"),
                fullTeam("Epsilon", "e1", "e2", "e3"),
                fullTeam("Zeta", "z1", "z2", "z3"));
    }

    private static MatchResult resultOf(Match match, UUID winnerId) {
        List<Placement> order = List.of(Placement.WINNER, Placement.INTERMEDIATE, Placement.ELIMINATED);
        List<TeamResult> results = new java.util.ArrayList<>();
        int placementIndex = 0;
        for (int i = 0; i < match.teamIds().size(); i++) {
            Placement placement = match.teamIds().get(i).equals(winnerId)
                    ? Placement.WINNER
                    : order.get(++placementIndex);
            results.add(new TeamResult(match.teamIds().get(i), placement));
        }
        return new MatchResult(match.id(), results, java.util.Map.of(), java.util.Map.of(),
                Duration.ofMinutes(5), Instant.now());
    }

    @Test
    void createTournamentStartsInRegistration() {
        Tournament tournament = this.manager.createTournament("Summer Cup");

        assertEquals(TournamentState.REGISTRATION, tournament.state());
        assertTrue(tournament.teamIds().isEmpty());
        assertTrue(this.manager.getActiveTournament().isPresent());
    }

    @Test
    void createTournamentRejectsSecondActiveTournament() {
        this.manager.createTournament("Summer Cup");
        assertThrows(TournamentException.class, () -> this.manager.createTournament("Winter Cup"));
    }

    @Test
    void createTournamentRejectsBlankName() {
        assertThrows(TournamentException.class, () -> this.manager.createTournament("  "));
        assertThrows(TournamentException.class, () -> this.manager.createTournament(null));
    }

    @Test
    void startRequiresAtLeastThreeTeams() {
        this.manager.createTournament("Summer Cup");
        this.teamManager.createTeam(player("A"), "Solo Team");

        TournamentException e = assertThrows(TournamentException.class, () -> this.manager.startTournament());
        assertTrue(e.getMessage().contains("at least 3 teams"));
    }

    @Test
    void startRejectsIncompleteTeams() {
        this.manager.createTournament("Summer Cup");
        Player captain = player("A");
        this.teamManager.createTeam(captain, "Incomplete");
        fullTeam("Alpha", "b1", "b2", "b3");
        fullTeam("Beta", "c1", "c2", "c3");

        TournamentException e = assertThrows(TournamentException.class, () -> this.manager.startTournament());
        assertTrue(e.getMessage().contains("incomplete"));
    }

    @Test
    void startLocksTeamsCapturesTeamIdsAndFiresEvent() {
        List<Team> teams = registerThreeFullTeams();
        this.manager.createTournament("Summer Cup");

        Tournament started = this.manager.startTournament();

        assertEquals(TournamentState.STARTING, started.state());
        assertTrue(started.startedAt() != null);
        assertEquals(3, started.teamIds().size());
        assertTrue(started.teamIds().containsAll(teams.stream().map(Team::id).toList()));
        assertTrue(teams.stream().allMatch(team -> this.teamManager.isLocked(team.id())));
        assertEquals(1, this.eventBus.count(TournamentStartedEvent.class));
        assertEquals(1, this.eventBus.count(RoundCreatedEvent.class));
        assertEquals(1, started.roundIds().size());
        assertTrue(this.roundManager.getCurrentRound().isPresent());
    }

    @Test
    void startNextRoundRunsTheCreatedRound() {
        registerThreeFullTeams();
        this.manager.createTournament("Summer Cup");
        this.manager.startTournament();

        Tournament running = this.manager.startNextRound();

        assertEquals(TournamentState.ROUND_RUNNING, running.state());
        Round round = this.roundManager.getCurrentRound().orElseThrow();
        assertEquals(RoundState.RUNNING, round.state());
        assertTrue(this.roundManager.getMatchesOfRound(round.id()).stream()
                .allMatch(match -> match.status() == MatchStatus.RUNNING));
        assertEquals(1, this.eventBus.count(RoundStartedEvent.class));
    }

    @Test
    void startNextRoundRejectedFromRegistration() {
        this.manager.createTournament("Summer Cup");
        assertThrows(TournamentException.class, () -> this.manager.startNextRound());
    }

    @Test
    void matchResultsAdvanceRoundsAndFinishTournament() {
        List<Team> teams = registerSixFullTeams();
        this.manager.createTournament("Summer Cup");
        this.manager.startTournament();
        this.manager.startNextRound();

        Round round1 = this.roundManager.getCurrentRound().orElseThrow();
        List<Match> round1Matches = this.roundManager.getMatchesOfRound(round1.id());
        assertEquals(2, round1Matches.size());

        this.manager.submitMatchResult(round1Matches.get(0).id(),
                resultOf(round1Matches.get(0), round1Matches.get(0).teamIds().get(0)));
        assertEquals(TournamentState.ROUND_RUNNING, this.manager.getState().orElseThrow());
        this.manager.submitMatchResult(round1Matches.get(1).id(),
                resultOf(round1Matches.get(1), round1Matches.get(1).teamIds().get(0)));

        Round round2 = this.roundManager.getCurrentRound().orElseThrow();
        assertEquals(2, round2.number());
        assertEquals(RoundState.RUNNING, round2.state());
        assertEquals(TournamentState.ROUND_RUNNING, this.manager.getState().orElseThrow());
        assertEquals(1, this.roundManager.getMatchesOfRound(round2.id()).size());

        Match finalMatch = this.roundManager.getMatchesOfRound(round2.id()).getFirst();
        Tournament finished = this.manager.submitMatchResult(finalMatch.id(),
                resultOf(finalMatch, finalMatch.teamIds().get(0)));

        assertEquals(TournamentState.FINISHED, finished.state());
        assertTrue(this.roundManager.isFinished());
        assertEquals(finalMatch.teamIds().get(0), this.manager.getChampionTeamId().orElseThrow());
        assertEquals(1, this.eventBus.count(TournamentFinishedEvent.class));
        assertEquals(3, this.eventBus.count(MatchFinishedEvent.class));
    }

    @Test
    void submitMatchResultRequiresRunningTournament() {
        registerThreeFullTeams();
        this.manager.createTournament("Summer Cup");
        this.manager.startTournament();

        Round round = this.roundManager.getCurrentRound().orElseThrow();
        Match match = this.roundManager.getMatchesOfRound(round.id()).getFirst();
        assertThrows(TournamentException.class, () -> this.manager.submitMatchResult(
                match.id(), resultOf(match, match.teamIds().getFirst())));
    }

    @Test
    void stopCancelsActiveMatches() {
        registerSixFullTeams();
        this.manager.createTournament("Summer Cup");
        this.manager.startTournament();
        this.manager.startNextRound();

        this.manager.stopTournament();

        assertTrue(this.roundManager.getMatches().stream()
                .allMatch(match -> match.status() == MatchStatus.CANCELLED));
        assertTrue(this.roundManager.isFinished());
    }

    @Test
    void startWithoutActiveTournamentFails() {
        assertThrows(TournamentException.class, () -> this.manager.startTournament());
    }

    @Test
    void forceStartCreatesTournamentWhenNoneActive() {
        fullTeam("Alpha", "a1", "a2", "a3");
        fullTeam("Beta", "b1", "b2", "b3");

        Tournament started = this.manager.forceStartTournament("TNTWars");

        assertEquals(TournamentState.STARTING, started.state());
        assertEquals("TNTWars", started.name());
        assertEquals(2, started.teamIds().size());
        assertTrue(this.teamManager.getTeams().stream().allMatch(Team::locked));
        assertEquals(1, this.eventBus.count(TournamentStartedEvent.class));
    }

    @Test
    void forceStartReusesRegistrationTournament() {
        fullTeam("Alpha", "a1", "a2", "a3");
        fullTeam("Beta", "b1", "b2", "b3");
        this.manager.createTournament("Summer Cup");

        Tournament started = this.manager.forceStartTournament("TNTWars");

        assertEquals("Summer Cup", started.name());
        assertEquals(1, this.manager.getTournaments().size());
    }

    @Test
    void forceStartSkipsTeamValidation() {
        fullTeam("Alpha", "a1", "a2", "a3");
        Player captain = player("b");
        this.teamManager.createTeam(captain, "Incomplete");

        Tournament started = this.manager.forceStartTournament("TNTWars");

        assertEquals(TournamentState.STARTING, started.state());
        assertEquals(2, started.teamIds().size());
    }

    @Test
    void forceStartRequiresAtLeastTwoTeams() {
        this.teamManager.createTeam(player("A"), "Solo Team");

        TournamentException e = assertThrows(TournamentException.class,
                () -> this.manager.forceStartTournament("TNTWars"));
        assertTrue(e.getMessage().contains("at least 2 teams"));
    }

    @Test
    void forceStartRejectedWhileRunning() {
        registerThreeFullTeams();
        this.manager.createTournament("Summer Cup");
        this.manager.startTournament();
        this.manager.startNextRound();

        assertThrows(TournamentException.class, () -> this.manager.forceStartTournament("TNTWars"));
    }

    @Test
    void pauseIsRejectedDuringRegistration() {
        this.manager.createTournament("Summer Cup");
        assertThrows(TournamentException.class, () -> this.manager.pauseTournament());
    }

    @Test
    void pauseAndResumeCycleReturnsToStarting() {
        registerThreeFullTeams();
        this.manager.createTournament("Summer Cup");
        this.manager.startTournament();

        Tournament paused = this.manager.pauseTournament();
        assertEquals(TournamentState.PAUSED, paused.state());

        Tournament resumed = this.manager.resumeTournament();
        assertEquals(TournamentState.STARTING, resumed.state());
    }

    @Test
    void resumeWithoutPauseIsRejected() {
        this.manager.createTournament("Summer Cup");
        assertThrows(TournamentException.class, () -> this.manager.resumeTournament());
    }

    @Test
    void stopCancelsAndUnlocksTeams() {
        registerThreeFullTeams();
        this.manager.createTournament("Summer Cup");
        this.manager.startTournament();
        assertTrue(this.teamManager.getTeams().stream().allMatch(Team::locked));

        Tournament cancelled = this.manager.stopTournament();

        assertEquals(TournamentState.CANCELLED, cancelled.state());
        assertTrue(cancelled.finishedAt() != null);
        assertFalse(this.teamManager.getTeams().stream().allMatch(Team::locked));
        assertEquals(0, this.eventBus.count(TournamentFinishedEvent.class));
    }

    @Test
    void stopRejectsFromTerminalStates() {
        registerThreeFullTeams();
        this.manager.createTournament("Summer Cup");
        this.manager.startTournament();
        this.manager.stopTournament();

        assertThrows(TournamentException.class, () -> this.manager.stopTournament());
        assertThrows(TournamentException.class, () -> this.manager.pauseTournament());
        assertThrows(TournamentException.class, () -> this.manager.resumeTournament());
    }

    @Test
    void finishIsOnlyAllowedFromRoundFinished() {
        registerThreeFullTeams();
        this.manager.createTournament("Summer Cup");
        this.manager.startTournament();

        assertThrows(TournamentException.class, () -> this.manager.finishTournament());
        assertEquals(0, this.eventBus.count(TournamentFinishedEvent.class));
    }

    @Test
    void newTournamentCanBeCreatedAfterCancelledOne() {
        registerThreeFullTeams();
        this.manager.createTournament("Summer Cup");
        this.manager.startTournament();
        this.manager.stopTournament();

        Tournament second = this.manager.createTournament("Winter Cup");
        assertEquals(TournamentState.REGISTRATION, second.state());
        assertEquals(2, this.manager.getTournaments().size());
    }
}
