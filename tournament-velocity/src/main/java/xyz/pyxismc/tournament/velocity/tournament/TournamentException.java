package xyz.pyxismc.tournament.velocity.tournament;

/**
 * Thrown when a tournament operation is invalid. The message is a
 * user-facing English sentence.
 */
public final class TournamentException extends RuntimeException {

    public TournamentException(String message) {
        super(message);
    }
}
