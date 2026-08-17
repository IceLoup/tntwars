package xyz.pyxismc.tournament.velocity.event;

import java.util.Objects;

import xyz.pyxismc.tournament.common.model.Match;

/**
 * Fired when the match server has been provisioned and the match
 * instructions have been published on its Redis channel.
 */
public record MatchProvisionedEvent(Match match, String serverId) {

    public MatchProvisionedEvent {
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(serverId, "serverId");
    }
}