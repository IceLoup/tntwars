package xyz.pyxismc.tournament.velocity.provision;

import xyz.pyxismc.tournament.common.message.ProvisionRequest;

/**
 * Boots a temporary match server for a provisioned match. The returned
 * server id must be unique and match the Velocity-registered server name
 * when it joins the proxy.
 */
public interface Provisioner {

    /** Provisions a server for the request, or throws on failure. */
    ProvisionResult provision(ProvisionRequest request) throws ProvisionException;
}