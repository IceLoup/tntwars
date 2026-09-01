package xyz.pyxismc.tournament.velocity.team;

import java.util.List;
import java.util.UUID;

import com.velocitypowered.api.proxy.ProxyServer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import xyz.pyxismc.tournament.common.message.JsonCodec;
import xyz.pyxismc.tournament.common.message.LobbyTeamSyncMessage;
import xyz.pyxismc.tournament.common.message.MessageChannels;
import xyz.pyxismc.tournament.common.model.Team;
import xyz.pyxismc.tournament.common.model.TeamPlayer;
import xyz.pyxismc.tournament.common.redis.TournamentRedis;
import xyz.pyxismc.tournament.velocity.event.TeamCreatedEvent;
import xyz.pyxismc.tournament.velocity.event.TeamDisbandedEvent;
import xyz.pyxismc.tournament.velocity.event.TeamInviteEvent;
import xyz.pyxismc.tournament.velocity.event.TeamJoinEvent;
import xyz.pyxismc.tournament.velocity.event.TeamLeaveEvent;
import xyz.pyxismc.tournament.velocity.event.TournamentEventBus;

/**
 * Bridges Velocity team state to the Paper lobby servers.
 *
 * <p>Whenever a team is created, joined, left or disbanded the full team
 * snapshot is published on the {@code LOBBY_TEAM_SYNC} Redis channel so the
 * lobby scoreboard stays accurate. Invitations additionally trigger a
 * clickable message to the invited player.</p>
 */
public final class LobbyTeamSync {

    private final ProxyServer proxy;
    private final TeamManager teamManager;
    private final TournamentRedis redis;
    private final JsonCodec codec;

    public LobbyTeamSync(
            ProxyServer proxy,
            TeamManager teamManager,
            TournamentRedis redis,
            TournamentEventBus eventBus,
            JsonCodec codec
    ) {
        this.proxy = proxy;
        this.teamManager = teamManager;
        this.redis = redis;
        this.codec = codec;

        eventBus.subscribe(TeamCreatedEvent.class, this::onTeamChanged);
        eventBus.subscribe(TeamJoinEvent.class, this::onTeamChanged);
        eventBus.subscribe(TeamLeaveEvent.class, this::onTeamChanged);
        eventBus.subscribe(TeamDisbandedEvent.class, this::onTeamChanged);
        eventBus.subscribe(TeamInviteEvent.class, this::onInvite);
    }

    private void onTeamChanged(Object event) {
        if (this.redis == null || !this.redis.isAvailable()) {
            return;
        }
        List<LobbyTeamSyncMessage.TeamEntry> entries = this.teamManager.getTeams().stream()
                .map(this::toEntry)
                .toList();
        this.redis.publish(
                MessageChannels.LOBBY_TEAM_SYNC,
                this.codec.toJson(new LobbyTeamSyncMessage(entries))
        );
    }

    private LobbyTeamSyncMessage.TeamEntry toEntry(Team team) {
        List<UUID> members = team.players().stream()
                .map(TeamPlayer::playerId)
                .toList();
        return new LobbyTeamSyncMessage.TeamEntry(team.id(), team.name(), members);
    }

    private void onInvite(TeamInviteEvent event) {
        this.proxy.getPlayer(event.invitedId()).ifPresent(target -> {
            Team team = event.team();
            String teamName = team.name();
            String inviterName = team.players().stream()
                    .filter(player -> player.playerId().equals(event.inviterId()))
                    .map(TeamPlayer::username)
                    .findFirst()
                    .orElse("Someone");

            Component message = Component.text()
                    .append(Component.text("[Team] ", NamedTextColor.DARK_AQUA))
                    .append(Component.text(teamName, NamedTextColor.AQUA, TextDecoration.BOLD))
                    .append(Component.text(" invited you" + (inviterName.equals("Someone") ? "" : " (" + inviterName + ")") + ". ", NamedTextColor.GRAY))
                    .append(Component.text("[Click to join]", NamedTextColor.GREEN, TextDecoration.BOLD)
                            .clickEvent(ClickEvent.runCommand("/team accept")))
                    .build();

            target.sendMessage(message);
        });
    }
}
