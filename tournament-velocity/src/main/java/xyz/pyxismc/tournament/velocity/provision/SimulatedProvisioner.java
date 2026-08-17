package xyz.pyxismc.tournament.velocity.provision;

import java.time.Duration;

import xyz.pyxismc.tournament.common.message.ProvisionRequest;

/**
 * Default provisioner: waits the configured startup delay, then derives a
 * stable server id from the match. The real Docker-based provisioning is
 * implemented in a later phase behind the same {@link Provisioner} interface.
 */
public final class SimulatedProvisioner implements Provisioner {

    private final Duration startupDelay;

    public SimulatedProvisioner(Duration startupDelay) {
        this.startupDelay = startupDelay;
    }

    @Override
    public ProvisionResult provision(ProvisionRequest request) throws ProvisionException {
        if (!this.startupDelay.isZero() && !this.startupDelay.isNegative()) {
            try {
                Thread.sleep(this.startupDelay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProvisionException("Provisioning interrupted for match " + request.matchId(), e);
            }
        }
        String shortId = request.matchId().toString().replace("-", "").substring(0, 8);
        return new ProvisionResult(request.template() + "-" + shortId);
    }
}