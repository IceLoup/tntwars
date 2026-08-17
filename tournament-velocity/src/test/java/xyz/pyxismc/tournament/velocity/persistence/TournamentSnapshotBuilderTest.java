package xyz.pyxismc.tournament.velocity.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xyz.pyxismc.tournament.common.dto.TournamentSnapshot;
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
import xyz.pyxismc.tournament.velocity.round.DefaultRoundStrategy;
import xyz.pyxismc.tournament.velocity.round.RoundManager;
import xyz.pyxismc.tournament.velocity.team.TeamManager;
import xyz.pyxismc.tournament.velocity.testutil.FakeEventBus;
import xyz.pyxismc.tournament.velocity.tournament.TournamentManager;

class TournamentSnapshotBuilderTest {

    private FakeEventBus eventBus;
    private TeamManager teamManager;
    private RoundManager roundManager;
    private TournamentManager tournamentManager;
    private TournamentSnapshotBuilder builder;

    @BeforeEach
    void setUp() {
        this.eventBus = new FakeEventBus();
        this.teamManager = new TeamManager(TournamentConfig.defaults(), this.eventBus);
        this.roundManager = new RoundManager(new DefaultRoundStrategy(), this.eventBus, 3);
        this.tournamentManager = new TournamentManager(
                TournamentConfig.defaults(), this.teamManager, this.roundManager, this.eventBus);
        this.builder = new TournamentSnapshotBuilder(this.teamManager, this.roundManager);
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

    private static MatchResult matchResult(Match match, int winnerIndex) {
        List<Placement> order = List.of(Placement.WINNER, Placement.INTERMEDIATE, Placement.ELIMINATED);
        List<TeamResult> results = new ArrayList<>();
        int placementIndex = 0;
        for (int i = 0; i < match.teamIds().size(); i++) {
            Placement placement = i == winnerIndex ? Placement.WINNER : order.get(++placementIndex);
            results.add(new TeamResult(match.teamIds().get(i), placement));
        }
        return new MatchResult(match.id(), results, Map.of(), Map.of(),
                Duration.ofMinutes(7), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    private void playOutTournament() {
        for (String name : List.of("Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta")) {
            fullTeam(name, name.toLowerCase() + "1", name.toLowerCase() + "2", name.toLowerCase() + "3");
        }
        this.tournamentManager.createTournament("Summer Cup");
        this.tournamentManager.startTournament();
        this.tournamentManager.startNextRound();

        Round round = this.roundManager.getCurrentRound().orElseThrow();
        List<Match> matches = this.roundManager.getMatchesOfRound(round.id());
        this.tournamentManager.submitMatchResult(matches.get(0).id(), matchResult(matches.get(0), 0));
        this.tournamentManager.submitMatchResult(matches.get(1).id(), matchResult(matches.get(1), 0));

        Round round2 = this.roundManager.getCurrentRound().orElseThrow();
        Match finalMatch = this.roundManager.getMatchesOfRound(round2.id()).getFirst();
        this.tournamentManager.submitMatchResult(finalMatch.id(), matchResult(finalMatch, 0));
    }

    @Test
    void buildCapturesFinishedTournament() {
        playOutTournament();
        Tournament tournament = this.tournamentManager.getActiveTournament().orElseThrow();

        TournamentSnapshot snapshot = this.builder.build(tournament);

        assertEquals(TournamentState.FINISHED, snapshot.tournament().state());
        assertEquals(6, snapshot.teams().size());
        assertEquals(2, snapshot.rounds().size());
        assertEquals(3, snapshot.matches().size());
        assertEquals(3, snapshot.results().size());
        assertTrue(snapshot.results().values().stream()
                .allMatch(result -> result.results().size() >= 2));
        assertTrue(snapshot.championTeamId() != null);
        assertTrue(snapshot.teamOf(snapshot.championTeamId()).isPresent());
    }

    @Test
    void buildCapturesCancelledTournament() {
        for (String name : List.of("Alpha", "Beta", "Gamma")) {
            fullTeam(name, name.toLowerCase() + "1", name.toLowerCase() + "2", name.toLowerCase() + "3");
        }
        this.tournamentManager.createTournament("Summer Cup");
        this.tournamentManager.startTournament();
        this.tournamentManager.startNextRound();
        this.tournamentManager.stopTournament();

        TournamentSnapshot snapshot = this.builder.build(
                this.tournamentManager.getActiveTournament().orElseThrow());

        assertEquals(TournamentState.CANCELLED, snapshot.tournament().state());
        assertTrue(snapshot.matches().stream().allMatch(match -> match.status() == MatchStatus.CANCELLED));
        assertTrue(snapshot.results().isEmpty());
        assertEquals(1, snapshot.rounds().size());
        assertEquals(RoundState.RUNNING, snapshot.rounds().getFirst().state());
    }
}