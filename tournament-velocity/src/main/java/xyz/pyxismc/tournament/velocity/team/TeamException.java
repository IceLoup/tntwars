package xyz.pyxismc.tournament.velocity.team;

/**
 * Thrown when a team operation is invalid. The message is a user-facing
 * English sentence, displayed to players via MiniMessage by the command layer.
 */
public final class TeamException extends RuntimeException {

    public TeamException(String message) {
        super(message);
    }
}
