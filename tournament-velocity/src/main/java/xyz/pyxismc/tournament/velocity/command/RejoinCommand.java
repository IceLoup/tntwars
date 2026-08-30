package xyz.pyxismc.tournament.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Optional;
import java.util.UUID;
import xyz.pyxismc.tournament.velocity.command.CommandMessages;
import xyz.pyxismc.tournament.velocity.config.TournamentConfig;
import xyz.pyxismc.tournament.velocity.event.MatchProvisionedEvent;
import xyz.pyxismc.tournament.velocity.event.MatchStartedEvent;
import xyz.pyxismc.tournament.velocity.event.MatchFinishedEvent;
import xyz.pyxismc.tournament.velocity.tournament.TournamentManager;
import xyz.pyxismc.tournament.velocity.team.TeamManager;
import xyz.pyxismc.tournament.velocity.tournament.TournamentException;
import xyz.pyxismc.tournament.velocity.event.TournamentEventBus;
import xyz.pyxismc.tournament.velocity.round.RoundManager;
import com.velocitypowered.api.proxy.ProxyServer;

/**
 * {@code /rejoin} command: allows players to rejoin their game if disconnected.
 */
public final class RejoinCommand implements SimpleCommand {

    private final TournamentManager tournamentManager;
    private final TeamManager teamManager;
    private final RoundManager roundManager;
    private final TournamentEventBus eventBus;
    private final TournamentConfig config;
    private final ProxyServer proxy;

    public RejoinCommand(
            TournamentManager tournamentManager,
            TeamManager teamManager,
            RoundManager roundManager,
            TournamentEventBus eventBus,
            TournamentConfig config,
            ProxyServer proxy) {
        this.tournamentManager = tournamentManager;
        this.teamManager = teamManager;
        this.roundManager = roundManager;
        this.eventBus = eventBus;
        this.config = config;
        this.proxy = proxy;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player)) {
            CommandMessages.error(source, "This command can only be used by players.");
            return;
        }

        Player player = (Player) source;
        UUID playerId = player.playerId();

        // Find the player's current team
        Optional<xyz.pyxismc.tournament.common.model.Team> teamOpt = teamManager.teamOfPlayer(playerId);
        if (teamOpt.isEmpty()) {
            CommandMessages.error(source, "You are not currently on a team.");
            return;
        }
        UUID teamId = teamOpt.get().id();

        // Find the player's current match
        UUID matchId = roundManager.getMatchIdForTeam(teamId);
        if (matchId == null) {
            CommandMessages.error(source, "Your team is not currently in a match.");
            return;
        }

        // Check if the match is still active (not finished)
        // Get the match and check its status
        Optional<xyz.pyxismc.tournament.common.model.Match> matchOpt = roundManager.getMatch(matchId);
        if (matchOpt.isEmpty()) {
            CommandMessages.error(source, "Could not find your match.");
            return;
        }

        xyz.pyxismc.tournament.common.model.Match match = matchOpt.get();
        if (match.status() == xyz.pyxismc.tournament.common.enums.MatchStatus.FINISHED) {
            CommandMessages.error(source, "Your match has already finished. You cannot rejoin.");
            return;
        }

        // Get the match server from the proxy using the serverId from the match
        RegisteredServer server = proxy.getServer(match.serverId()).orElse(null);
        if (server == null) {
            CommandMessages.error(source, "Could not find your match server.");
            return;
        }

        // Transfer the player to the match server
        player.createConnectionRequest(server).connect();
        CommandMessages.success(source, "Rejoining your match...");
    }
}