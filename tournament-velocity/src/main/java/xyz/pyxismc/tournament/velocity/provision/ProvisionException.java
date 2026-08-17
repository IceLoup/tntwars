package xyz.pyxismc.tournament.velocity.provision;

/** Thrown when a match server could not be provisioned. */
public final class ProvisionException extends RuntimeException {

    public ProvisionException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProvisionException(String message) {
        super(message);
    }
}