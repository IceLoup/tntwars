package xyz.pyxismc.tournament.velocity.provision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import xyz.pyxismc.tournament.common.enums.TeamRole;
import xyz.pyxismc.tournament.common.enums.TournamentState;
import xyz.pyxismc.tournament.common.model.Match;
import xyz.pyxismc.tournament.common.model.Team;
import xyz.pyxismc.tournament.common.model.TeamPlayer;
import xyz.pyxismc.tournament.common.model.Tournament;
import xyz.pyxismc.tournament.common.result.MatchResult;
import xyz.pyxismc.tournament.common.result.Placement;
import xyz.pyxismc.tournament.common.result.TeamResult;
import xyz.pyxismc.tournament.velocity.event.MatchFailedEvent;
import xyz.pyxismc.tournament.velocity.event.MatchFinishedEvent;
import xyz.pyxismc.tournament.velocity.event.TournamentCancelledEvent;
import xyz.pyxismc.tournament.velocity.round.DefaultRoundStrategy;
import xyz.pyxismc.tournament.velocity.round.RoundManager;
import xyz.pyxismc.tournament.velocity.testutil.FakeDockerGateway;
import xyz.pyxismc.tournament.velocity.testutil.FakeEventBus;
import xyz.pyxismc.tournament.velocity.testutil.FakeServerRegistry;

class MatchServerLifecycleTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(MatchServerLifecycleTest.class);

    private FakeEventBus eventBus;
    private RoundManager roundManager;
    private FakeServerRegistry registry;
    private FakeDockerGateway docker;
    private MatchServerLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        this.eventBus = new FakeEventBus();
        this.roundManager = new RoundManager(new DefaultRoundStrategy(), this.eventBus, 3);
        this.registry = new FakeServerRegistry();
        this.docker = new FakeDockerGateway();
        this.lifecycle = new MatchServerLifecycle(this.registry, this.roundManager, this.docker, 15, LOGGER);
    }

    private static Team team(int index) {
        UUID id = UUID.randomUUID();
        return new Team(id, "Team " + index, id,
                List.of(new TeamPlayer(id, "player" + index, TeamRole.CAPTAIN)), false);
    }

    private static List<Team> teams(int count) {
        return IntStream.range(0, count).mapToObj(MatchServerLifecycleTest::team).toList();
    }

    private static MatchResult resultOf(Match match) {
        List<Placement> order = List.of(Placement.WINNER, Placement.INTERMEDIATE, Placement.ELIMINATED);
        List<TeamResult> results = new ArrayList<>();
        int placementIndex = 0;
        for (int i = 0; i < match.teamIds().size(); i++) {
            Placement placement = i == 0 ? Placement.WINNER : order.get(++placementIndex);
            results.add(new TeamResult(match.teamIds().get(i), placement));
        }
        return new MatchResult(match.id(), results, java.util.Map.of(), java.util.Map.of(),
                Duration.ofMinutes(5), Instant.now());
    }

    private Match provisionedMatch() {
        UUID tournamentId = UUID.randomUUID();
        this.roundManager.createFirstRound(tournamentId, teams(6));
        this.roundManager.startRound(this.roundManager.getCurrentRound().orElseThrow().id());
        Match match = this.roundManager.getMatches().get(0);
        Match updated = this.roundManager.assignServer(match.id(), "game-" + match.id().toString().substring(0, 8));
        this.lifecycle.track(updated.serverId());
        return updated;
    }

    @Test
    void finishedMatchTearsDownItsServer() {
        Match match = provisionedMatch();
        String serverId = match.serverId();

        this.lifecycle.onMatchFinished(new MatchFinishedEvent(match, resultOf(match)));

        assertEquals(0, this.registry.size());
        assertEquals(List.of(serverId), this.docker.stopped());
        assertEquals(List.of(serverId), this.docker.removed());
    }

    @Test
    void failedMatchTearsDownItsServer() {
        Match match = provisionedMatch();
        String serverId = match.serverId();

        this.lifecycle.onMatchFailed(new MatchFailedEvent(match, "Server crashed"));

        assertEquals(0, this.registry.size());
        assertEquals(List.of(serverId), this.docker.stopped());
        assertEquals(List.of(serverId), this.docker.removed());
    }

    @Test
    void cancelledTournamentTearsDownEveryRunningMatchServer() {
        Match first = provisionedMatch();
        Match second = this.roundManager.assignServer(this.roundManager.getMatches().get(1).id(),
                "game-" + this.roundManager.getMatches().get(1).id().toString().substring(0, 8));
        this.lifecycle.track(second.serverId());
        UUID tournamentId = this.roundManager.getRounds().get(0).tournamentId();
        Tournament tournament = new Tournament(
                tournamentId, "Summer Cup", TournamentState.ROUND_RUNNING,
                List.of(), List.of(), Instant.now(), Instant.now(), null);

        this.lifecycle.onTournamentCancelled(new TournamentCancelledEvent(tournament));

        assertEquals(0, this.registry.size());
        assertEquals(List.of(first.serverId(), second.serverId()), this.docker.stopped());
        assertEquals(2, this.docker.removed().size());
    }

    @Test
    void closeTearsDownEveryTrackedServer() {
        Match first = provisionedMatch();
        Match second = this.roundManager.assignServer(this.roundManager.getMatches().get(1).id(),
                "game-" + this.roundManager.getMatches().get(1).id().toString().substring(0, 8));
        this.lifecycle.track(second.serverId());

        this.lifecycle.close();

        assertEquals(0, this.registry.size());
        assertEquals(Set.of(first.serverId(), second.serverId()), new java.util.HashSet<>(this.docker.stopped()));
        assertEquals(2, this.docker.removed().size());
    }

    @Test
    void matchWithoutServerIsIgnored() {
        UUID tournamentId = UUID.randomUUID();
        this.roundManager.createFirstRound(tournamentId, teams(6));
        this.roundManager.startRound(this.roundManager.getCurrentRound().orElseThrow().id());
        Match match = this.roundManager.getMatch(this.roundManager.getMatches().get(0).id()).orElseThrow();

        this.lifecycle.onMatchFinished(new MatchFinishedEvent(match, resultOf(match)));

        assertEquals(0, this.registry.size());
        assertTrue(this.docker.stopped().isEmpty());
        assertTrue(this.docker.removed().isEmpty());
    }

    @Test
    void simulatedModeTearsDownRegistryButNotContainers() {
        Match match = provisionedMatch();
        MatchServerLifecycle simulated = new MatchServerLifecycle(this.registry, this.roundManager, null, 15, LOGGER);
        simulated.track(match.serverId());

        simulated.onMatchFinished(new MatchFinishedEvent(match, resultOf(match)));

        assertEquals(0, this.registry.size());
        assertTrue(this.docker.stopped().isEmpty(), "no docker available in simulated mode");
    }
}
