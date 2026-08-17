package xyz.pyxismc.tournament.velocity.round;

/** Thrown when a round, group or match operation is invalid. */
public class RoundException extends RuntimeException {

    public RoundException(String message) {
        super(message);
    }
}