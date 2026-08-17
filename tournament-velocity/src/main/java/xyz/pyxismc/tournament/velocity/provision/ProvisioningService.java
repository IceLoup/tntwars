package xyz.pyxismc.tournament.velocity.provision;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;

import xyz.pyxismc.tournament.common.enums.MatchStatus;
import xyz.pyxismc.tournament.common.message.JsonCodec;
import xyz.pyxismc.tournament.common.message.MatchStartMessage;
import xyz.pyxismc.tournament.common.message.MessageChannels;
import xyz.pyxismc.tournament.common.message.ProvisionRequest;
import xyz.pyxismc.tournament.common.model.Match;
import xyz.pyxismc.tournament.common.model.Team;
import xyz.pyxismc.tournament.common.redis.TournamentRedis;
import xyz.pyxismc.tournament.velocity.config.TournamentConfig;
import xyz.pyxismc.tournament.velocity.event.MatchProvisionedEvent;
import xyz.pyxismc.tournament.velocity.event.MatchStartedEvent;
import xyz.pyxismc.tournament.velocity.event.TournamentEventBus;
import xyz.pyxismc.tournament.velocity.round.RoundManager;
import xyz.pyxismc.tournament.velocity.team.TeamManager;
import xyz.pyxismc.tournament.velocity.tournament.TournamentManager;

/**
 * Consumes {@link MatchStartedEvent}s, pushes a {@link ProvisionRequest} on
 * the Redis queue and, once a provisioner reports the server ready, publishes
 * the {@link MatchStartMessage} on the server's channel and fires a
 * {@link MatchProvisionedEvent}.
 */
public final class ProvisioningService implements AutoCloseable {

    private final TournamentRedis redis;
    private final JsonCodec codec;
    private final Provisioner provisioner;
    private final TournamentEventBus eventBus;
    private final RoundManager roundManager;
    private final TournamentManager tournamentManager;
    private final TeamManager teamManager;
    private final TournamentConfig config;
    private final Logger logger;

    private final Thread worker;
    private volatile boolean running;

    public ProvisioningService(
            TournamentRedis redis,
            JsonCodec codec,
            Provisioner provisioner,
            TournamentEventBus eventBus,
            RoundManager roundManager,
            TournamentManager tournamentManager,
            TeamManager teamManager,
            TournamentConfig config,
            Logger logger
    ) {
        this.redis = redis;
        this.codec = codec;
        this.provisioner = provisioner;
        this.eventBus = eventBus;
        this.roundManager = roundManager;
        this.tournamentManager = tournamentManager;
        this.teamManager = teamManager;
        this.config = config;
        this.logger = logger;
        this.eventBus.subscribe(MatchStartedEvent.class, this::onMatchStarted);
        this.worker = new Thread(this::drainQueue, "tournament-provisioner");
        this.worker.setDaemon(true);
    }

    /** Starts the queue consumer thread. */
    public void start() {
        this.running = true;
        this.worker.start();
    }

    /** Enqueues a provisioning request for every started match. */
    void onMatchStarted(MatchStartedEvent event) {
        Match match = event.match();
        if (match.serverId() != null) {
            return;
        }
        ProvisionRequest request = new ProvisionRequest(match.id(), this.config.server().template(), match.teamIds());
        this.redis.push(MessageChannels.PROVISION_QUEUE, this.codec.toJson(request));
        this.logger.info("Provisioning requested for match {}", match.id());
    }

    private void drainQueue() {
        while (this.running) {
            String payload = this.redis.pop(MessageChannels.PROVISION_QUEUE, 5);
            if (payload == null) {
                continue;
            }
            try {
                handleRequest(this.codec.fromJson(payload, ProvisionRequest.class));
            } catch (ProvisionException e) {
                this.logger.warn("Provisioning failed: {}", e.getMessage());
            } catch (RuntimeException e) {
                this.logger.warn("Cannot process provisioning request: {}", e.toString());
            }
        }
    }

    private void handleRequest(ProvisionRequest request) {
        Match match = this.roundManager.getMatch(request.matchId()).orElse(null);
        if (match == null || match.status() != MatchStatus.RUNNING) {
            this.logger.warn("Stale provisioning request for match {}", request.matchId());
            return;
        }
        ProvisionResult result = this.provisioner.provision(request);
        String tournamentName = this.tournamentManager.getActiveTournament()
                .map(tournament -> tournament.name())
                .orElse("Tournament");
        Map<UUID, List<UUID>> playersByTeam = new LinkedHashMap<>();
        for (UUID teamId : match.teamIds()) {
            Team team = this.teamManager.getTeam(teamId).orElse(null);
            if (team == null) {
                throw new ProvisionException("Team " + teamId + " no longer exists.");
            }
            playersByTeam.put(teamId, team.players().stream()
                    .map(player -> player.playerId())
                    .toList());
        }
        MatchStartMessage startMessage = new MatchStartMessage(
                match.id(),
                result.serverId(),
                tournamentName,
                match.teamIds(),
                playersByTeam,
                this.config.tournament().playersPerTeam());
        this.redis.publish(MessageChannels.matchChannel(result.serverId()), this.codec.toJson(startMessage));
        this.eventBus.fire(new MatchProvisionedEvent(match, result.serverId()));
        this.logger.info("Match {} provisioned on server {}", match.id(), result.serverId());
    }

    @Override
    public void close() {
        this.running = false;
        this.worker.interrupt();
    }
}