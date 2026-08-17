package xyz.pyxismc.tournament.velocity.event;

import java.util.Objects;


import xyz.pyxismc.tournament.common.model.Team;

/**
 * Fired when a team is disbanded.
 */
public record TeamDisbandedEvent(Team team) {

    public TeamDisbandedEvent {
        Objects.requireNonNull(team, "team");
    }
}
