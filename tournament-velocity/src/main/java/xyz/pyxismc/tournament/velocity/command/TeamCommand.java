package xyz.pyxismc.tournament.velocity.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import xyz.pyxismc.tournament.common.model.Team;
import xyz.pyxismc.tournament.velocity.team.TeamException;
import xyz.pyxismc.tournament.velocity.team.TeamManager;

/**
 * {@code /team} command: create, invite, accept, leave, disband and info.
 * Open to every player; team rules (captain-only invite/disband, locked
 * teams during a tournament) are enforced by the {@link TeamManager}.
 */
public final class TeamCommand implements SimpleCommand {

    private final ProxyServer proxy;
    private final TeamManager teamManager;

    public TeamCommand(ProxyServer proxy, TeamManager teamManager) {
        this.proxy = proxy;
        this.teamManager = teamManager;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            CommandMessages.info(source, "/team <create|invite|accept|leave|disband|info>");
            return;
        }
        if (!(source instanceof Player player)) {
            CommandMessages.error(source, "Only players can use this command.");
            return;
        }

        switch (args[0].toLowerCase()) {
            case "create" -> create(player, args);
            case "invite" -> invite(player, args);
            case "accept" -> accept(player);
            case "leave" -> leave(player);
            case "disband" -> disband(player);
            case "info" -> info(player, args);
            default -> CommandMessages.info(player, "/team <create|invite|accept|leave|disband|info>");
        }
    }

    private void create(Player player, String[] args) {
        if (args.length < 2 || args[1].isBlank()) {
            CommandMessages.error(player, "Usage: /team create <name>");
            return;
        }
        try {
            Team team = this.teamManager.createTeam(asCommonPlayer(player), joinName(args));
            CommandMessages.success(player, "Team '" + team.name() + "' created. You are the captain.");
        } catch (TeamException e) {
            CommandMessages.error(player, e.getMessage());
        }
    }

    private void invite(Player player, String[] args) {
        if (args.length < 2) {
            CommandMessages.error(player, "Usage: /team invite <player>");
            return;
        }
        Optional<Player> target = this.proxy.getPlayer(args[1]);
        if (target.isEmpty()) {
            CommandMessages.error(player, "Player '" + args[1] + "' is not online.");
            return;
        }
        try {
            this.teamManager.invitePlayer(player.getUniqueId(), target.get().getUniqueId());
            CommandMessages.success(player, "Invitation sent to " + args[1] + ".");
        } catch (TeamException e) {
            CommandMessages.error(player, e.getMessage());
        }
    }

    private void accept(Player player) {
        Optional<UUID> teamId = this.teamManager.pendingInvitationOf(player.getUniqueId());
        if (teamId.isEmpty()) {
            CommandMessages.error(player, "You have no pending invitation.");
            return;
        }
        try {
            Team team = this.teamManager.acceptInvite(asCommonPlayer(player), teamId.get());
            CommandMessages.success(player, "You joined team '" + team.name() + "'.");
        } catch (TeamException e) {
            CommandMessages.error(player, e.getMessage());
        }
    }

    private void leave(Player player) {
        try {
            this.teamManager.leaveTeam(asCommonPlayer(player));
            CommandMessages.success(player, "You left your team.");
        } catch (TeamException e) {
            CommandMessages.error(player, e.getMessage());
        }
    }

    private void disband(Player player) {
        String name = this.teamManager.teamOfPlayer(player.getUniqueId()).map(Team::name).orElse("");
        try {
            this.teamManager.disbandTeam(asCommonPlayer(player));
            CommandMessages.success(player, "Team '" + name + "' has been disbanded.");
        } catch (TeamException e) {
            CommandMessages.error(player, e.getMessage());
        }
    }

    private void info(Player player, String[] args) {
        Optional<Team> team;
        if (args.length >= 2) {
            Optional<Player> target = this.proxy.getPlayer(args[1]);
            if (target.isEmpty()) {
                CommandMessages.error(player, "Player '" + args[1] + "' is not online.");
                return;
            }
            team = this.teamManager.teamOfPlayer(target.get().getUniqueId());
            if (team.isEmpty()) {
                CommandMessages.error(player, "That player is not in a team.");
                return;
            }
        } else {
            team = this.teamManager.teamOfPlayer(player.getUniqueId());
            if (team.isEmpty()) {
                CommandMessages.info(player, "You are not in a team. Create one with /team create <name>.");
                return;
            }
        }
        player.sendRichMessage(TeamInfoRenderer.render(team.get()));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            return List.of("create", "invite", "accept", "leave", "disband", "info");
        }
        if ((args[0].equalsIgnoreCase("invite") || args[0].equalsIgnoreCase("info"))
                && args.length == 2) {
            return this.proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .toList();
        }
        return List.of();
    }

    private static String joinName(String[] args) {
        List<String> parts = new ArrayList<>(List.of(args));
        parts.removeFirst();
        return String.join(" ", parts);
    }

    private static xyz.pyxismc.tournament.common.model.Player asCommonPlayer(Player player) {
        return new xyz.pyxismc.tournament.common.model.Player(player.getUniqueId(), player.getUsername());
    }
}
