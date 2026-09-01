package xyz.pyxismc.tournament.velocity;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.zaxxer.hikari.HikariDataSource;

import org.slf4j.Logger;
import net.kyori.adventure.text.Component;

import xyz.pyxismc.tournament.common.message.JsonCodec;
import xyz.pyxismc.tournament.common.message.MatchReadyMessage;
import xyz.pyxismc.tournament.common.message.MatchResultMessage;
import xyz.pyxismc.tournament.common.message.MessageChannels;
import xyz.pyxismc.tournament.common.model.Match;
import xyz.pyxismc.tournament.common.model.Team;
import xyz.pyxismc.tournament.common.model.TeamPlayer;
import xyz.pyxismc.tournament.common.model.Tournament;
import xyz.pyxismc.tournament.common.redis.JedisTournamentRedis;
import xyz.pyxismc.tournament.common.redis.TournamentRedis;
import xyz.pyxismc.tournament.velocity.command.TeamCommand;
import xyz.pyxismc.tournament.velocity.command.TournamentCommand;
import xyz.pyxismc.tournament.velocity.command.RejoinCommand;
import xyz.pyxismc.tournament.velocity.config.TournamentConfig;
import xyz.pyxismc.tournament.velocity.config.TournamentConfig.DatabaseSettings;
import xyz.pyxismc.tournament.velocity.config.TournamentConfig.DockerSettings;
import xyz.pyxismc.tournament.velocity.config.TournamentConfig.RedisSettings;
import xyz.pyxismc.tournament.velocity.docker.CliDockerGateway;
import xyz.pyxismc.tournament.velocity.docker.DockerEnvironment;
import xyz.pyxismc.tournament.velocity.docker.DockerGateway;
import xyz.pyxismc.tournament.velocity.event.MatchFailedEvent;
import xyz.pyxismc.tournament.velocity.event.MatchFinishedEvent;
import xyz.pyxismc.tournament.velocity.event.MatchProvisionedEvent;
import xyz.pyxismc.tournament.velocity.event.TournamentCancelledEvent;
import xyz.pyxismc.tournament.velocity.event.TournamentEventBus;
import xyz.pyxismc.tournament.velocity.event.TournamentFinishedEvent;
import xyz.pyxismc.tournament.velocity.event.VelocityEventBus;
import xyz.pyxismc.tournament.velocity.persistence.PostgresTournamentRepository;
import xyz.pyxismc.tournament.velocity.persistence.TournamentRepository;
import xyz.pyxismc.tournament.velocity.persistence.TournamentSnapshotBuilder;
import xyz.pyxismc.tournament.velocity.provision.DockerProvisioner;
import xyz.pyxismc.tournament.velocity.provision.MatchServerLifecycle;
import xyz.pyxismc.tournament.velocity.provision.Provisioner;
import xyz.pyxismc.tournament.velocity.provision.ProvisioningService;
import xyz.pyxismc.tournament.velocity.provision.ServerRegistry;
import xyz.pyxismc.tournament.velocity.provision.SimulatedProvisioner;
import xyz.pyxismc.tournament.velocity.round.DefaultRoundStrategy;
import xyz.pyxismc.tournament.velocity.round.RoundManager;
import xyz.pyxismc.tournament.velocity.team.TeamManager;
import xyz.pyxismc.tournament.velocity.team.LobbyTeamSync;
import xyz.pyxismc.tournament.velocity.tournament.TournamentException;
import xyz.pyxismc.tournament.velocity.tournament.TournamentManager;

/**
 * Entry point of the Velocity plugin.
 *
 * <p>Responsibilities (built phase by phase): teams, tournament, rounds,
 * groups, matches, temporary servers, PostgreSQL persistence and Redis
 * communication. Velocity is the single authority of the tournament.</p>
 */
@Plugin(
        id = "tournament",
        name = "Tournament",
        version = "1.0.0-SNAPSHOT",
        description = "Minecraft 3-player team tournament platform",
        authors = {"IceLoup"}
)
public final class TournamentVelocityPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private TournamentConfig config;
    private TournamentEventBus eventBus;
    private TeamManager teamManager;
    private RoundManager roundManager;
    private TournamentManager tournamentManager;

private HikariDataSource dataSource;
     private TournamentRepository repository;
     private TournamentSnapshotBuilder snapshotBuilder;
     private final Map<UUID, Set<UUID>> queuedPlayersPerMatch = new ConcurrentHashMap<>();

     private TournamentRedis redis;
     private ProvisioningService provisioningService;
     private MatchServerLifecycle matchServerLifecycle;
     private JsonCodec jsonCodec = new JsonCodec();

    @Inject
    public TournamentVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        this.config = TournamentConfig.load(this.dataDirectory, this.logger);
        this.eventBus = new VelocityEventBus(this.proxy);
        this.teamManager = new TeamManager(this.config, this.eventBus);
        this.roundManager = new RoundManager(
                new DefaultRoundStrategy(),
                this.eventBus,
                this.config.tournament().maxTeamsPerMatch());
        this.tournamentManager = new TournamentManager(this.config, this.teamManager, this.roundManager, this.eventBus);

        DockerSettings dockerSettings = this.config.server().docker();
        DockerGateway dockerGateway = dockerSettings.enabled()
                ? new CliDockerGateway(dockerSettings.command(), this.logger)
                : null;
        this.matchServerLifecycle = new MatchServerLifecycle(
                new ProxyServerRegistry(this.proxy),
                this.roundManager,
                dockerGateway,
                (int) this.config.server().shutdownTimeout().toSeconds(),
                this.logger);

        this.logger.info("Tournament enabled (playersPerTeam={}, maxTeamsPerMatch={}, lobby={}, provisioning={})",
                this.config.tournament().playersPerTeam(),
                this.config.tournament().maxTeamsPerMatch(),
                this.config.lobby().server(),
                dockerSettings.enabled() ? "docker" : "simulated");

        initPersistence();
        this.snapshotBuilder = new TournamentSnapshotBuilder(this.teamManager, this.roundManager);
        initRedis();
        initLobbyTeamSync();
        this.eventBus.subscribe(MatchProvisionedEvent.class, this::onMatchProvisioned);
        this.eventBus.subscribe(MatchFinishedEvent.class, this.matchServerLifecycle::onMatchFinished);
        this.eventBus.subscribe(MatchFailedEvent.class, this.matchServerLifecycle::onMatchFailed);
        this.eventBus.subscribe(TournamentCancelledEvent.class, this.matchServerLifecycle::onTournamentCancelled);

        CommandManager commandManager = this.proxy.getCommandManager();
commandManager.register(commandManager.metaBuilder("team")
                .aliases("teams")
                .plugin(this)
                .build(),
                new TeamCommand(this.proxy, this.teamManager));
        commandManager.register(commandManager.metaBuilder("tournament")
                .aliases("tntwar", "tnt")
                .plugin(this)
                .build(),
                new TournamentCommand(this.config, this.tournamentManager, this.roundManager, this.teamManager,
                        this.repository));
        commandManager.register(commandManager.metaBuilder("rejoin")
                .plugin(this)
                .build(),
                new RejoinCommand(this.tournamentManager, this.teamManager, this.roundManager, this.eventBus, this.config, this.proxy));
    }

    @Subscribe
    public void onTournamentFinished(TournamentFinishedEvent event) {
        saveAsync(event.tournament());
    }

    @Subscribe
    public void onTournamentCancelled(TournamentCancelledEvent event) {
        saveAsync(event.tournament());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (this.snapshotBuilder != null) {
            this.tournamentManager.getActiveTournament().ifPresent(this::saveAsync);
        }
        if (this.provisioningService != null) {
            this.provisioningService.close();
        }
        if (this.matchServerLifecycle != null) {
            this.matchServerLifecycle.close();
        }
        if (this.redis != null) {
            this.redis.close();
        }
        CommandManager commandManager = this.proxy.getCommandManager();
        commandManager.unregister("team");
        commandManager.unregister("tournament");
        if (this.dataSource != null) {
            this.dataSource.close();
        }
        this.logger.info("Tournament disabled");
    }

    private void initRedis() {
        RedisSettings redisSettings = this.config.redis();
        try {
            this.redis = new JedisTournamentRedis(
                    redisSettings.host(), redisSettings.port(), redisSettings.password());
            DockerSettings dockerSettings = this.config.server().docker();
            Provisioner provisioner;
            if (dockerSettings.enabled()) {
                provisioner = new DockerProvisioner(
                        new CliDockerGateway(dockerSettings.command(), this.logger),
                        new ProxyServerRegistry(this.proxy),
                        new DockerEnvironment(redisSettings.host(), redisSettings.port(), redisSettings.password()),
                        this.config.server().startupTimeout(),
                        this.config.server().template(),
                        dockerSettings.image(),
                        dockerSettings.network(),
                        dockerSettings.port(),
                        this.logger);
            } else {
                provisioner = new SimulatedProvisioner(this.config.server().startupTimeout());
            }
            this.provisioningService = new ProvisioningService(
                    this.redis,
                    this.jsonCodec,
                    provisioner,
                    this.eventBus,
                    this.roundManager,
                    this.tournamentManager,
                    this.teamManager,
                    this.config,
                    this.logger);
            this.provisioningService.start();
this.redis.subscribe(MessageChannels.MATCH_READY, this::onMatchReady);
             this.redis.subscribe(MessageChannels.MATCH_RESULT, this::onMatchResult);
             this.redis.subscribe(MessageChannels.MATCH_READY_FOR_PLAYERS, this::onMatchReadyForPlayers);
            this.logger.info("Redis connected: {}:{}", redisSettings.host(), redisSettings.port());
        } catch (Exception e) {
            this.logger.warn("Redis unavailable, match provisioning disabled: {}", e.getMessage());
            if (this.redis != null) {
                this.redis.close();
                this.redis = null;
            }
        }
    }

    private void initLobbyTeamSync() {
        if (this.redis == null) {
            this.logger.warn("Redis unavailable, lobby team scoreboard sync disabled");
            return;
        }
        new LobbyTeamSync(this.proxy, this.teamManager, this.redis, this.eventBus, this.jsonCodec);
        this.logger.info("Lobby team scoreboard sync enabled");
    }

    private void onMatchProvisioned(MatchProvisionedEvent event) {
        Match match = this.roundManager.assignServer(event.match().id(), event.serverId());
        this.matchServerLifecycle.track(event.serverId());
        this.logger.info("Match {} provisioned on {}", match.id(), match.serverId());
    }

    private void onMatchReady(String payload) {
        try {
            MatchReadyMessage message = this.jsonCodec.fromJson(payload, MatchReadyMessage.class);
            this.logger.info("Match {} ready on server {}", message.matchId(), message.serverId());
            Match match = this.roundManager.getMatch(message.matchId()).orElse(null);
            if (match == null) {
                this.logger.warn("Match {} not found in round manager", message.matchId());
                return;
            }
            Set<UUID> playerIds = new HashSet<>();
            for (UUID teamId : match.teamIds()) {
                Team team = this.teamManager.getTeam(teamId).orElse(null);
                if (team != null) {
                    for (TeamPlayer tp : team.players()) {
                        playerIds.add(tp.playerId());
                    }
                }
            }
            Set<UUID> queued = this.queuedPlayersPerMatch.computeIfAbsent(message.matchId(), k -> ConcurrentHashMap.newKeySet());
            for (UUID playerId : playerIds) {
                this.proxy.getPlayer(playerId).ifPresent(player -> {
                    queued.add(playerId);
                    // Send action bar message
                    player.sendActionBar(Component.text("Vous êtes en file d'attente pour rejoindre le serveur de jeu..."));
                });
            }
        } catch (RuntimeException e) {
            this.logger.warn("Malformed match-ready message ignored: {}", e.getMessage());
        }
    }

    private void transferTeamPlayers(UUID matchId, RegisteredServer server) {
        Match match = this.roundManager.getMatch(matchId).orElse(null);
        if (match == null) {
            this.logger.warn("Match {} no longer exists, players not transferred", matchId);
            return;
        }
        for (UUID teamId : match.teamIds()) {
            Team team = this.teamManager.getTeam(teamId).orElse(null);
            if (team == null) {
                continue;
            }
            for (TeamPlayer player : team.players()) {
                this.proxy.getPlayer(player.playerId()).ifPresent(proxyPlayer ->
                        proxyPlayer.createConnectionRequest(server).connect());
                // Delay between transfers to prevent lag (5 players per second = 200ms delay)
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        this.logger.info("Match {} players transferred to {}", matchId, server.getServerInfo().getName());
    }

    private void onMatchResult(String payload) {
        MatchResultMessage message = null;
        try {
            message = this.jsonCodec.fromJson(payload, MatchResultMessage.class);
            Match match = this.roundManager.getMatch(message.matchId()).orElse(null);
            if (match == null || !message.serverId().equals(match.serverId())) {
                this.logger.warn("Untrusted result for match {} from server {} ignored",
                        message.matchId(), message.serverId());
                return;
            }
            this.tournamentManager.submitMatchResult(message.matchId(), message.result());
            this.logger.info("Result recorded for match {}", message.matchId());
        } catch (TournamentException e) {
            this.logger.warn("Match result rejected: {}", e.getMessage());
            if (message != null) {
                try {
                    this.roundManager.failMatch(message.matchId(), e.getMessage());
                } catch (RuntimeException ignored) {
                    // match already failed or gone
                }
            }
        } catch (RuntimeException e) {
            this.logger.warn("Malformed match-result message ignored: {}", e.getMessage());
        }
    }

    private void initPersistence() {
        DatabaseSettings database = this.config.database();
        try {
            this.dataSource = new HikariDataSource();
            this.dataSource.setJdbcUrl("jdbc:postgresql://" + database.host() + ":" + database.port()
                    + "/" + database.database());
            this.dataSource.setUsername(database.username());
            this.dataSource.setPassword(database.password());
            this.dataSource.setMaximumPoolSize(5);
            this.dataSource.setPoolName("tournament-db");
            this.repository = new PostgresTournamentRepository(this.dataSource);
            this.logger.info("PostgreSQL connected: {}:{}/{}",
                    database.host(), database.port(), database.database());
        } catch (Exception e) {
            this.logger.warn("PostgreSQL unavailable, tournament history will not be persisted: {}",
                    e.getMessage());
            if (this.dataSource != null) {
                this.dataSource.close();
                this.dataSource = null;
            }
        }
    }

    private void saveAsync(Tournament tournament) {
        if (this.repository == null || this.snapshotBuilder == null) {
            return;
        }
        this.proxy.getScheduler().buildTask(this, () -> {
            try {
                this.repository.saveTournament(this.snapshotBuilder.build(tournament));
            } catch (Exception e) {
                this.logger.warn("Failed to persist tournament {}: {}", tournament.id(), e.getMessage());
            }
        }).schedule();
}
    
    private void onMatchReadyForPlayers(String payload) {
        try {
            UUID matchId = UUID.fromString(payload);
            this.logger.info("Received match ready for players signal for match {}", matchId);
            Set<UUID> queued = this.queuedPlayersPerMatch.remove(matchId);
            if (queued == null || queued.isEmpty()) {
                this.logger.debug("No players queued for match {}", matchId);
                return;
            }
            Match match = this.roundManager.getMatch(matchId).orElse(null);
            if (match == null) {
                this.logger.warn("Match {} not found for player transfer", matchId);
                return;
            }
            this.logger.info("Transferring {} queued players for match {}", queued.size(), matchId);
            for (UUID playerId : queued) {
                this.proxy.getPlayer(playerId).ifPresent(player -> {
                    // Send action bar message
                    player.sendActionBar(Component.text("Transfert vers le serveur de jeu en cours..."));
                    // Transfer player
                    RegisteredServer server = this.proxy.getServer(match.serverId()).orElse(null);
                    if (server != null) {
                        player.createConnectionRequest(server).connect();
                    } else {
                        this.logger.warn("Server {} not found for player {} transfer", match.serverId(), playerId);
                    }
                });
            }
        } catch (IllegalArgumentException e) {
            this.logger.warn("Invalid match ID received in MATCH_READY_FOR_PLAYERS: {}", payload);
        } catch (Exception e) {
            this.logger.warn("Error processing MATCH_READY_FOR_PLAYERS: {}", e.getMessage());
        }
    }
    
    /** Adapts the Velocity proxy to the {@link ServerRegistry} interface. */
    private static final class ProxyServerRegistry implements ServerRegistry {

        private final ProxyServer proxy;

        ProxyServerRegistry(ProxyServer proxy) {
            this.proxy = proxy;
        }

        @Override
        public java.util.Optional<RegisteredServer> get(String serverId) {
            return this.proxy.getServer(serverId);
        }

        @Override
        public void register(String serverId, java.net.InetSocketAddress address) {
            this.proxy.registerServer(new ServerInfo(serverId, address));
        }

        @Override
        public void unregister(String serverId) {
            this.proxy.getServer(serverId)
                    .ifPresent(server -> this.proxy.unregisterServer(server.getServerInfo()));
        }
    }

    public TournamentConfig config() {
        return this.config;
    }

    public TeamManager teamManager() {
        return this.teamManager;
    }

    public TournamentManager tournamentManager() {
        return this.tournamentManager;
    }
}
