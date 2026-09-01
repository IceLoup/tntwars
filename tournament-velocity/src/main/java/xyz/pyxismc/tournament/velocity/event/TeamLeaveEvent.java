package xyz.pyxismc.tournament.velocity.event;

import java.util.Objects;
import java.util.UUID;

import xyz.pyxismc.tournament.common.model.Team;

/**
 * Fired when a player leaves their team.
 */
public record TeamLeaveEvent(Team team, UUID playerId) {

    public TeamLeaveEvent {
        Objects.requireNonNull(team, "team");
        Objects.requireNonNull(playerId, "playerId");
    }
}
