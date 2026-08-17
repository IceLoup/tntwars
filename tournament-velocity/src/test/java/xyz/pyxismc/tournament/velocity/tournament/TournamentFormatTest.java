package xyz.pyxismc.tournament.velocity.tournament;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xyz.pyxismc.tournament.common.enums.MatchStatus;
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
import xyz.pyxismc.tournament.velocity.event.RoundFinishedEvent;
import xyz.pyxismc.tournament.velocity.event.TournamentFinishedEvent;
import xyz.pyxismc.tournament.velocity.round.DefaultRoundStrategy;
import xyz.pyxismc.tournament.velocity.round.RoundManager;
import xyz.pyxismc.tournament.velocity.team.TeamManager;
import xyz.pyxismc.tournament.velocity.testutil.FakeEventBus;

/**
 * End-to-end validation of the default tournament format: 12 full teams play
 * 3 rounds (4x3 teams, then 2x2 teams, then 1x2 teams) and exactly one
 * champion emerges. Only match winners advance; eliminated teams never
 * reappear.
 */
class TournamentFormatTest {

    private static final int MAX_TEAMS_PER_MATCH = 3;

    private FakeEventBus eventBus;
    private TeamManager teamManager;
    private RoundManager roundManager;
    private TournamentManager manager;

    @BeforeEach
    void setUp() {
        this.eventBus = new FakeEventBus();
        this.teamManager = new TeamManager(TournamentConfig.defaults(), this.eventBus);
        this.roundManager = new RoundManager(new DefaultRoundStrategy(), this.eventBus, MAX_TEAMS_PER_MATCH);
        this.manager = new TournamentManager(
                TournamentConfig.defaults(), this.teamManager, this.roundManager, this.eventBus);
    }

    private List<Team> registerTwelveFullTeams() {
        List<String> names = List.of("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot",
                "Golf", "Hotel", "India", "Juliet", "Kilo", "Lima");
        return names.stream().map(name -> fullTeam(name, name.toLowerCase() + "1",
                name.toLowerCase() + "2", name.toLowerCase() + "3")).toList();
    }

    private Team fullTeam(String teamName, String... players) {
        Player captain = new Player(UUID.randomUUID(), players[0]);
        Team team = this.teamManager.createTeam(captain, teamName);
        for (int i = 1; i < players.length; i++) {
            Player member = new Player(UUID.randomUUID(), players[i]);
            this.teamManager.invitePlayer(captain.uuid(), member.uuid());
            this.teamManager.acceptInvite(member, team.id());
        }
        return team;
    }

    private static MatchResult resultOf(Match match, UUID winnerId) {
        List<TeamResult> results = new ArrayList<>();
        int placementIndex = 0;
        for (int i = 0; i < match.teamIds().size(); i++) {
            Placement placement = match.teamIds().get(i).equals(winnerId)
                    ? Placement.WINNER
                    : (placementIndex++ == 0 ? Placement.INTERMEDIATE : Placement.ELIMINATED);
            results.add(new TeamResult(match.teamIds().get(i), placement));
        }
        return new MatchResult(match.id(), results, java.util.Map.of(), java.util.Map.of(),
                Duration.ofMinutes(5), Instant.now());
    }

    /** Submits results for every running match; team 0 of each match wins. */
    private void playCurrentRound(List<UUID> teamOrder) {
        for (Match match : this.roundManager.getMatches().stream()
                .filter(candidate -> candidate.status() == MatchStatus.RUNNING)
                .toList()) {
            this.manager.submitMatchResult(match.id(), resultOf(match, match.teamIds().get(0)));
        }
    }

    @Test
    void twelveTeamsProduceFourThreeRoundsAndOneChampion() {
        List<Team> teams = registerTwelveFullTeams();
        this.manager.createTournament("Summer Cup");
        this.manager.startTournament();
        this.manager.startNextRound();

        // Round 1: 4 matches of 3 teams (teams sorted by name).
        assertEquals(1, this.roundManager.getRounds().size());
        assertEquals(4, this.roundManager.getMatchesOfRound(this.roundManager.getRounds().get(0).id()).size());
        assertTrue(this.roundManager.getMatches().stream()
                .allMatch(match -> match.teamIds().size() == MAX_TEAMS_PER_MATCH));

        playCurrentRound(teams.stream().map(Team::id).toList());

        // Round 2: 2 matches of 2 teams (4 winners rebalanced, no single-team match).
        assertEquals(2, this.roundManager.getRounds().size());
        Round second = this.roundManager.getRounds().get(1);
        assertEquals(2, second.number());
        assertEquals(2, this.roundManager.getMatchesOfRound(second.id()).size());
        assertTrue(this.roundManager.getMatchesOfRound(second.id()).stream()
                .allMatch(match -> match.teamIds().size() == 2));

        playCurrentRound(teams.stream().map(Team::id).toList());

        // Round 3: the final, one match of 2 teams.
        assertEquals(3, this.roundManager.getRounds().size());
        Round third = this.roundManager.getRounds().get(2);
        assertEquals(3, third.number());
        assertEquals(1, this.roundManager.getMatchesOfRound(third.id()).size());
        assertEquals(2, this.roundManager.getMatchesOfRound(third.id()).get(0).teamIds().size());

        playCurrentRound(teams.stream().map(Team::id).toList());

        Tournament tournament = this.manager.getActiveTournament().orElseThrow();
        assertEquals(TournamentState.FINISHED, tournament.state());
        assertNotNull(tournament.finishedAt());
        UUID champion = this.manager.getChampionTeamId().orElseThrow();
        assertEquals(3, this.roundManager.getRounds().size());
        assertEquals(1, this.eventBus.count(TournamentFinishedEvent.class));
        assertEquals(3, this.eventBus.count(RoundFinishedEvent.class));
        assertEquals(4 + 2 + 1, this.eventBus.count(MatchFinishedEvent.class));

        // Only winners advanced: every round-2/3 team won its previous match.
        List<UUID> firstWinners = firstWinnersOf(1);
        List<UUID> secondWinners = firstWinnersOf(2);
        assertTrue(this.roundManager.getMatchesOfRound(third.id()).get(0).teamIds().containsAll(secondWinners));
        assertEquals(champion, secondWinners.getFirst());
        assertTrue(firstWinners.containsAll(secondWinners));
        assertEquals(teams.get(0).id(), champion, "the alphabetically first team wins everything");
    }

    private List<UUID> firstWinnersOf(int roundNumber) {
        Round round = this.roundManager.getRounds().stream()
                .filter(candidate -> candidate.number() == roundNumber)
                .findFirst()
                .orElseThrow();
        return this.roundManager.getMatchesOfRound(round.id()).stream()
                .map(match -> match.teamIds().get(0))
                .toList();
    }

    @Test
    void threeTeamsFinishInASingleMatch() {
        fullTeam("Alpha", "a1", "a2", "a3");
        fullTeam("Beta", "b1", "b2", "b3");
        fullTeam("Gamma", "g1", "g2", "g3");
        this.manager.createTournament("Blitz");
        this.manager.startTournament();
        this.manager.startNextRound();

        assertEquals(1, this.roundManager.getRounds().size());
        assertEquals(1, this.roundManager.getMatches().size());
        assertEquals(3, this.roundManager.getMatches().get(0).teamIds().size());

        playCurrentRound(List.of());

        Tournament tournament = this.manager.getActiveTournament().orElseThrow();
        assertEquals(TournamentState.FINISHED, tournament.state());
        assertEquals(this.roundManager.getMatches().get(0).teamIds().get(0),
                this.manager.getChampionTeamId().orElseThrow());
        assertEquals(1, this.roundManager.getRounds().size(), "no second round for a 3-team tournament");
    }
}