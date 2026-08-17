package xyz.pyxismc.tournament.common.model;

import java.util.Objects;
import java.util.UUID;

import xyz.pyxismc.tournament.common.enums.TeamRole;

/**
 * A player within a team, with its role.
 */
public record TeamPlayer(UUID playerId, String username, TeamRole role) {

    public TeamPlayer {
        Objects.requireNonNull(playerId, "playerId");
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        Objects.requireNonNull(role, "role");
    }

    public TeamPlayer withRole(TeamRole role) {
        return new TeamPlayer(this.playerId, this.username, role);
    }
}
