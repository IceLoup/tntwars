package xyz.pyxismc.tournament.velocity.provision;

import java.util.Objects;

/** Outcome of a successful provisioning. */
public record ProvisionResult(String serverId) {

    public ProvisionResult {
        Objects.requireNonNull(serverId, "serverId");
    }
}