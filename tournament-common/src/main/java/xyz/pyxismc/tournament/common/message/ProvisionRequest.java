package xyz.pyxismc.tournament.common.message;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Provisioning request pushed to the Redis queue by Velocity. A provisioner
 * (simulated for now, Docker later) consumes it and boots a match server.
 */
public record ProvisionRequest(UUID matchId, String template, List<UUID> teamIds) {

    public ProvisionRequest {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(template, "template");
        teamIds = List.copyOf(teamIds);
    }
}