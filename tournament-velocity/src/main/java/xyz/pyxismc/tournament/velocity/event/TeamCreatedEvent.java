package xyz.pyxismc.tournament.velocity.event;

import java.util.Objects;


import xyz.pyxismc.tournament.common.model.Team;

/**
 * Fired when a team is created.
 */
public record TeamCreatedEvent(Team team) {

    public TeamCreatedEvent {
        Objects.requireNonNull(team, "team");
    }
}
