package xyz.pyxismc.tournament.common.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A player participating in the tournament.
 */
public record Player(UUID uuid, String username) {

    public Player {
        Objects.requireNonNull(uuid, "uuid");
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
    }

    public Player withUsername(String username) {
        return new Player(this.uuid, username);
    }
}
