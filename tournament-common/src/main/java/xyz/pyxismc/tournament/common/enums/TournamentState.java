package xyz.pyxismc.tournament.common.enums;

/**
 * Lifecycle state of a tournament.
 *
 * <p>State transitions are centralized in the Velocity plugin
 * (TournamentStateMachine): no other class may change a tournament's state
 * directly.</p>
 */
public enum TournamentState {

    /** Teams are being created and can be edited. */
    REGISTRATION,

    /** The tournament is starting: teams are locked, rounds are being built. */
    STARTING,

    /** At least one round is currently running. */
    ROUND_RUNNING,

    /** The current round finished, results are processed before the next round. */
    ROUND_FINISHED,

    /** The tournament is over, a winner was determined. */
    FINISHED,

    /** The tournament was cancelled by an administrator. */
    CANCELLED,

    /** The tournament was paused by an administrator. */
    PAUSED
}
