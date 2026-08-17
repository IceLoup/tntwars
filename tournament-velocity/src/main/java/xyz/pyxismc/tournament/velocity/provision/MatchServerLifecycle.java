package xyz.pyxismc.tournament.velocity.provision;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;

import xyz.pyxismc.tournament.common.model.Match;
import xyz.pyxismc.tournament.common.model.Round;
import xyz.pyxismc.tournament.velocity.docker.DockerException;
import xyz.pyxismc.tournament.velocity.docker.DockerGateway;
import xyz.pyxismc.tournament.velocity.event.MatchFailedEvent;
import xyz.pyxismc.tournament.velocity.event.MatchFinishedEvent;
import xyz.pyxismc.tournament.velocity.event.TournamentCancelledEvent;
import xyz.pyxismc.tournament.velocity.round.RoundManager;

/**
 * Lifecycle of temporary match servers: tracks every provisioned server and
 * tears it down (unregister from Velocity, stop and remove the container)
 * once its match finishes, fails or its tournament is cancelled. Also closes
 * everything on proxy shutdown.
 */
public final class MatchServerLifecycle implements AutoCloseable {

    private final ServerRegistry registry;
    private final RoundManager roundManager;
    private final DockerGateway docker;
    private final int shutdownTimeoutSeconds;
    private final Logger logger;
    private final Set<String> servers = ConcurrentHashMap.newKeySet();

    public MatchServerLifecycle(
            ServerRegistry registry,
            RoundManager roundManager,
            DockerGateway docker,
            int shutdownTimeoutSeconds,
            Logger logger
    ) {
        this.registry = registry;
        this.roundManager = roundManager;
        this.docker = docker;
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
        this.logger = logger;
    }

    /** Called when a match server has been provisioned successfully. */
    public void track(String serverId) {
        this.servers.add(serverId);
    }

    public void onMatchFinished(MatchFinishedEvent event) {
        teardown(event.match());
    }

    public void onMatchFailed(MatchFailedEvent event) {
        teardown(event.match());
    }

    public void onTournamentCancelled(TournamentCancelledEvent event) {
        Set<UUID> roundIds = this.roundManager.getRounds().stream()
                .filter(round -> round.tournamentId().equals(event.tournament().id()))
                .map(Round::id)
                .collect(Collectors.toSet());
        this.roundManager.getMatches().stream()
                .filter(match -> roundIds.contains(match.roundId()))
                .forEach(this::teardown);
    }

    @Override
    public void close() {
        this.servers.stream().toList().forEach(serverId -> {
            this.registry.unregister(serverId);
            stopContainer(serverId);
        });
    }

    private void teardown(Match match) {
        String serverId = match.serverId();
        if (serverId == null) {
            return;
        }
        this.registry.unregister(serverId);
        stopContainer(serverId);
    }

    private void stopContainer(String serverId) {
        if (this.docker == null || !this.servers.remove(serverId)) {
            return;
        }
        try {
            this.docker.stopContainer(serverId, this.shutdownTimeoutSeconds);
            this.docker.removeContainer(serverId);
            this.logger.info("Match server {} torn down", serverId);
        } catch (DockerException e) {
            this.logger.warn("Failed to tear down match server {}: {}", serverId, e.toString());
        }
    }
}
