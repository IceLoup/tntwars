package xyz.pyxismc.tournament.velocity.event;

import java.util.Objects;
import java.util.UUID;

import xyz.pyxismc.tournament.common.model.Team;

/**
 * Fired when a captain invites a player to their team.
 */
public record TeamInviteEvent(Team team, UUID inviterId, UUID invitedId) {

    public TeamInviteEvent {
        Objects.requireNonNull(team, "team");
        Objects.requireNonNull(inviterId, "inviterId");
        Objects.requireNonNull(invitedId, "invitedId");
    }
}
