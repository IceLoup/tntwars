package xyz.pyxismc.tournament.common.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A team of up to {@code players-per-team} players.
 *
 * <p>A team needs 1 captain and exactly 3 players to enter the tournament.
 * It becomes locked when the tournament starts. A player belongs to at most
 * one team. The team never holds player-removal logic: the team manager
 * (Velocity) validates sizes and membership against the configuration.</p>
 */
public record Team(
        UUID id,
        String name,
        UUID captainId,
        List<TeamPlayer> players,
        boolean locked
) {

    public Team {
        Objects.requireNonNull(id, "id");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(captainId, "captainId");
        players = List.copyOf(players);
    }

    public Team withName(String name) {
        return new Team(this.id, name, this.captainId, this.players, this.locked);
    }

    public Team withCaptainId(UUID captainId) {
        return new Team(this.id, this.name, captainId, this.players, this.locked);
    }

    public Team withPlayers(List<TeamPlayer> players) {
        return new Team(this.id, this.name, this.captainId, players, this.locked);
    }

    public Team withLocked(boolean locked) {
        return new Team(this.id, this.name, this.captainId, this.players, locked);
    }
}
