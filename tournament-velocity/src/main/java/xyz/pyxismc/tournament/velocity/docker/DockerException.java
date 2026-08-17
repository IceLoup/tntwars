package xyz.pyxismc.tournament.velocity.docker;

/** Signals a failure to talk to the Docker daemon or to manage a container. */
public final class DockerException extends RuntimeException {

    public DockerException(String message) {
        super(message);
    }

    public DockerException(String message, Throwable cause) {
        super(message, cause);
    }
}
