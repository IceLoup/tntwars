package xyz.pyxismc.tournament.velocity.event;

import java.util.Objects;
import java.util.UUID;

import xyz.pyxismc.tournament.common.model.Team;

/**
 * Fired when a player joins a team (via invite acceptance).
 */
public record TeamJoinEvent(Team team, UUID playerId) {

    public TeamJoinEvent {
        Objects.requireNonNull(team, "team");
        Objects.requireNonNull(playerId, "playerId");
    }
}
