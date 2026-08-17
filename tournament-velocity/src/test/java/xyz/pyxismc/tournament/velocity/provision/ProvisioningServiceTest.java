package xyz.pyxismc.tournament.velocity.provision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import xyz.pyxismc.tournament.common.message.JsonCodec;
import xyz.pyxismc.tournament.common.message.MatchStartMessage;
import xyz.pyxismc.tournament.common.message.MessageChannels;
import xyz.pyxismc.tournament.common.message.ProvisionRequest;
import xyz.pyxismc.tournament.common.model.Player;
import xyz.pyxismc.tournament.common.model.Team;
import xyz.pyxismc.tournament.velocity.config.TournamentConfig;
import xyz.pyxismc.tournament.velocity.event.MatchProvisionedEvent;
import xyz.pyxismc.tournament.velocity.round.DefaultRoundStrategy;
import xyz.pyxismc.tournament.velocity.round.RoundManager;
import xyz.pyxismc.tournament.velocity.team.TeamManager;
import xyz.pyxismc.tournament.velocity.testutil.FakeEventBus;
import xyz.pyxismc.tournament.velocity.testutil.InMemoryTournamentRedis;
import xyz.pyxismc.tournament.velocity.tournament.TournamentManager;

/**
 * The worker consumes the queue: to keep the tests deterministic, requests
 * are queued (service created but not started) before the worker is started.
 */
class ProvisioningServiceTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProvisioningServiceTest.class);

    private InMemoryTournamentRedis redis;
    private FakeEventBus eventBus;
    private TeamManager teamManager;
    private RoundManager roundManager;
    private TournamentManager tournamentManager;
    private ProvisioningService service;

    @BeforeEach
    void setUp() {
        this.redis = new InMemoryTournamentRedis();
        this.eventBus = new FakeEventBus();
        this.teamManager = new TeamManager(TournamentConfig.defaults(), this.eventBus);
        this.roundManager = new RoundManager(new DefaultRoundStrategy(), this.eventBus, 3);
        this.tournamentManager = new TournamentManager(
                TournamentConfig.defaults(), this.teamManager, this.roundManager, this.eventBus);
    }

    @AfterEach
    void tearDown() {
        this.service.close();
        this.redis.close();
    }

    private void createService(Provisioner provisioner) {
        this.service = new ProvisioningService(
                this.redis,
                new JsonCodec(),
                provisioner,
                this.eventBus,
                this.roundManager,
                this.tournamentManager,
                this.teamManager,
                TournamentConfig.defaults(),
                LOGGER);
        // Mirrors the plugin wiring: a provisioned match gets its server id.
        this.eventBus.subscribe(MatchProvisionedEvent.class, event ->
                this.roundManager.assignServer(event.match().id(), event.serverId()));
    }

    private void startThreeTeamTournament() {
        for (String name : List.of("Alpha", "Beta", "Gamma")) {
            fullTeam(name, name.toLowerCase() + "1", name.toLowerCase() + "2", name.toLowerCase() + "3");
        }
        this.tournamentManager.createTournament("Summer Cup");
        this.tournamentManager.startTournament();
        this.tournamentManager.startNextRound();
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

    private List<MatchProvisionedEvent> provisionedEvents() {
        return this.eventBus.firedEvents().stream()
                .filter(MatchProvisionedEvent.class::isInstance)
                .map(MatchProvisionedEvent.class::cast)
                .toList();
    }

    @Test
    void startedMatchesAreProvisionedAndPublished() throws InterruptedException {
        createService(new SimulatedProvisioner(Duration.ZERO));
        startThreeTeamTournament();
        this.service.start();
        Thread.sleep(300);

        assertEquals(1, provisionedEvents().size());
        assertEquals(1, this.redis.recordedPublishes().size());
        assertTrue(this.redis.recordedPublishes().getFirst().startsWith("tournament:match:game-"));
    }

    @Test
    void matchStartMessageCarriesMatchContext() throws InterruptedException {
        createService(new SimulatedProvisioner(Duration.ZERO));
        startThreeTeamTournament();
        this.service.start();
        Thread.sleep(300);

        String payload = this.redis.recordedPublishes().getFirst().split("\u0000", 2)[1];
        MatchStartMessage message = new JsonCodec().fromJson(payload, MatchStartMessage.class);
        assertEquals("Summer Cup", message.tournamentName());
        assertEquals(3, message.playersPerTeam());
        assertEquals(3, message.teamIds().size());
        assertEquals(3, message.playersByTeam().size());
        assertEquals(3, message.playersByTeam().values().iterator().next().size());
        assertTrue(message.playersByTeam().keySet().containsAll(message.teamIds()));
        assertTrue(message.serverId().startsWith("game-"));
    }

    @Test
    void provisionedServerIdIsAssignedToMatch() throws InterruptedException {
        createService(new SimulatedProvisioner(Duration.ZERO));
        startThreeTeamTournament();
        this.service.start();
        Thread.sleep(300);

        MatchProvisionedEvent event = provisionedEvents().getFirst();
        assertEquals(event.serverId(), this.roundManager.getMatch(event.match().id()).orElseThrow().serverId());
        assertTrue(this.roundManager.getMatches().stream()
                .allMatch(match -> match.serverId() != null));
    }

    @Test
    void oneRequestPerMatchIsPushedOnTheQueue() throws InterruptedException {
        createService(new SimulatedProvisioner(Duration.ZERO));
        for (String name : List.of("Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta")) {
            fullTeam(name, name.toLowerCase() + "1", name.toLowerCase() + "2", name.toLowerCase() + "3");
        }
        this.tournamentManager.createTournament("Summer Cup");
        this.tournamentManager.startTournament();
        this.tournamentManager.startNextRound();
        // Service not started: the requests must still be queued.
        JsonCodec codec = new JsonCodec();
        List<String> payloads = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String payload = this.redis.pop(MessageChannels.PROVISION_QUEUE, 1);
            if (payload != null) {
                payloads.add(payload);
            }
        }
        assertEquals(2, payloads.size());
        for (String payload : payloads) {
            ProvisionRequest request = codec.fromJson(payload, ProvisionRequest.class);
            assertEquals("game", request.template());
            assertEquals(3, request.teamIds().size());
        }
        // Queue fully drained by the reads.
        assertTrue(this.redis.pop(MessageChannels.PROVISION_QUEUE, 1) == null);
    }

    @Test
    void staleRequestsAreIgnored() throws InterruptedException {
        this.redis.push(MessageChannels.PROVISION_QUEUE, new JsonCodec().toJson(
                new ProvisionRequest(UUID.randomUUID(), "game", List.of(UUID.randomUUID()))));
        createService(new SimulatedProvisioner(Duration.ZERO));
        this.service.start();
        Thread.sleep(300);
        assertTrue(provisionedEvents().isEmpty());
        assertTrue(this.redis.recordedPublishes().isEmpty());
    }

    @Test
    void failedProvisioningIsLoggedAndDropped() throws InterruptedException {
        createService(request -> {
            throw new ProvisionException("no docker daemon");
        });
        startThreeTeamTournament();
        this.service.start();
        Thread.sleep(300);
        assertTrue(provisionedEvents().isEmpty());
        assertTrue(this.redis.recordedPublishes().isEmpty());
    }
}