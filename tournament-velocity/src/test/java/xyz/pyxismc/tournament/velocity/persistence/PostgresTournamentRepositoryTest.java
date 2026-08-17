package xyz.pyxismc.tournament.velocity.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import xyz.pyxismc.tournament.common.dto.TournamentSnapshot;
import xyz.pyxismc.tournament.common.dto.TournamentSummary;
import xyz.pyxismc.tournament.common.enums.MatchStatus;
import xyz.pyxismc.tournament.common.enums.RoundState;
import xyz.pyxismc.tournament.common.enums.TournamentState;
import xyz.pyxismc.tournament.common.model.Match;
import xyz.pyxismc.tournament.common.model.PlayerMatchStats;
import xyz.pyxismc.tournament.common.model.Round;
import xyz.pyxismc.tournament.common.model.Team;
import xyz.pyxismc.tournament.common.model.TeamMatchStats;
import xyz.pyxismc.tournament.common.model.TeamPlayer;
import xyz.pyxismc.tournament.common.model.Tournament;
import xyz.pyxismc.tournament.common.result.MatchResult;
import xyz.pyxismc.tournament.common.result.Placement;
import xyz.pyxismc.tournament.common.result.TeamResult;

/**
 * Round-trip tests against H2 in PostgreSQL mode: the exact same SQL and
 * repository code used with a real PostgreSQL server.
 */
class PostgresTournamentRepositoryTest {

    private HikariDataSource dataSource;
    private PostgresTournamentRepository repository;

    @BeforeEach
    void setUp() {
        this.dataSource = new HikariDataSource();
        this.dataSource.setJdbcUrl("jdbc:h2:mem:repo_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        this.dataSource.setUsername("sa");
        this.dataSource.setPassword("");
        this.dataSource.setMaximumPoolSize(2);
        this.repository = new PostgresTournamentRepository(this.dataSource);
    }

    @AfterEach
    void tearDown() {
        this.dataSource.close();
    }

    private static Team team(String name, String... usernames) {
        UUID teamId = UUID.randomUUID();
        List<TeamPlayer> players = new ArrayList<>();
        for (int i = 0; i < usernames.length; i++) {
            players.add(new TeamPlayer(UUID.randomUUID(), usernames[i],
                    i == 0 ? xyz.pyxismc.tournament.common.enums.TeamRole.CAPTAIN
                            : xyz.pyxismc.tournament.common.enums.TeamRole.MEMBER));
        }
        return new Team(teamId, name, players.getFirst().playerId(), players, false);
    }

    private static MatchResult resultOf(Match match, UUID winnerTeamId, Duration duration) {
        List<TeamResult> results = new ArrayList<>();
        Map<UUID, TeamMatchStats> teamStats = new java.util.LinkedHashMap<>();
        for (int i = 0; i < match.teamIds().size(); i++) {
            UUID teamId = match.teamIds().get(i);
            Placement placement = teamId.equals(winnerTeamId) ? Placement.WINNER : Placement.ELIMINATED;
            if (i == 1 && match.teamIds().size() > 2) {
                placement = Placement.INTERMEDIATE;
            }
            results.add(new TeamResult(teamId, placement));
            teamStats.put(teamId, new TeamMatchStats(teamId, 10 + i, 2 + i));
        }
        Map<UUID, PlayerMatchStats> playerStats = Map.of(
                UUID.randomUUID(), new PlayerMatchStats(UUID.randomUUID(), winnerTeamId, 5, 1, 2, 300));
        return new MatchResult(match.id(), results, teamStats, playerStats, duration,
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    private static TournamentSnapshot snapshot() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID tournamentId = UUID.randomUUID();
        Team alpha = team("Alpha", "a1", "a2", "a3");
        Team beta = team("Beta", "b1", "b2", "b3");
        Team gamma = team("Gamma", "c1", "c2", "c3");
        Tournament tournament = new Tournament(
                tournamentId, "Summer Cup", TournamentState.FINISHED,
                List.of(alpha.id(), beta.id(), gamma.id()),
                List.of(),
                now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)), now);

        UUID round1Id = UUID.randomUUID();
        Round round1 = new Round(round1Id, tournamentId, 1, RoundState.FINISHED, List.of());

        Match match1 = new Match(UUID.randomUUID(), round1Id,
                List.of(alpha.id(), beta.id(), gamma.id()), "match-server-1", MatchStatus.FINISHED);
        MatchResult result1 = resultOf(match1, alpha.id(), Duration.ofMinutes(5));
        UUID championId = alpha.id();

        return new TournamentSnapshot(
                tournament,
                List.of(alpha, beta, gamma),
                List.of(round1),
                List.of(match1),
                Map.of(match1.id(), result1),
                championId);
    }

    @Test
    void saveAndListRoundTrip() {
        TournamentSnapshot stored = snapshot();
        this.repository.saveTournament(stored);

        List<TournamentSummary> summaries = this.repository.listTournaments(10);

        assertEquals(1, summaries.size());
        TournamentSummary summary = summaries.getFirst();
        assertEquals(stored.tournament().id(), summary.id());
        assertEquals("Summer Cup", summary.name());
        assertEquals(TournamentState.FINISHED, summary.state());
        assertEquals(3, summary.teamCount());
        assertEquals(stored.tournament().finishedAt(), summary.finishedAt());
        assertEquals(stored.championTeamId(), summary.championTeamId());
    }

    @Test
    void saveAndLoadFullRoundTrip() {
        TournamentSnapshot stored = snapshot();
        this.repository.saveTournament(stored);

        TournamentSnapshot loaded = this.repository.getTournament(stored.tournament().id()).orElseThrow();

        assertEquals(stored.tournament().name(), loaded.tournament().name());
        assertEquals(stored.tournament().state(), loaded.tournament().state());
        assertEquals(stored.tournament().finishedAt(), loaded.tournament().finishedAt());
        assertEquals(stored.teams().size(), loaded.teams().size());
        assertEquals(3, loaded.teams().getFirst().players().size());
        assertEquals("Alpha", loaded.teams().getFirst().name());
        assertEquals(stored.rounds().size(), loaded.rounds().size());
        assertEquals(stored.matches().size(), loaded.matches().size());

        MatchResult storedResult = stored.results().values().iterator().next();
        MatchResult loadedResult = loaded.results().values().iterator().next();
        assertEquals(storedResult.duration(), loadedResult.duration());
        assertEquals(storedResult.finishedAt(), loadedResult.finishedAt());
        assertEquals(storedResult.results().size(), loadedResult.results().size());
        assertEquals(storedResult.teamStats().size(), loadedResult.teamStats().size());
        assertEquals(storedResult.playerStats().size(), loadedResult.playerStats().size());
        assertEquals(5, loadedResult.playerStats().values().iterator().next().kills());
        assertEquals(stored.championTeamId(), loaded.championTeamId());
    }

    @Test
    void missingTournamentReturnsEmpty() {
        assertTrue(this.repository.getTournament(UUID.randomUUID()).isEmpty());
    }

    @Test
    void listIsEmptyBeforeAnySave() {
        assertTrue(this.repository.listTournaments(10).isEmpty());
    }
}