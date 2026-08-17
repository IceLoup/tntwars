package xyz.pyxismc.tournament.common.enums;

/**
 * Lifecycle state of a single match.
 *
 * <p>State transitions are handled by the match manager on Velocity;
 * a match can be resumed after an error whenever possible.</p>
 */
public enum MatchStatus {

    /** The match exists but no server was provisioned yet. */
    CREATED,

    /** A server is being provisioned (Docker). */
    PROVISIONING,

    /** The server is up and ready to receive players. */
    READY,

    /** Players are being transferred to the server. */
    STARTING,

    /** The match is being played. */
    RUNNING,

    /** The match ended, result is being collected. */
    FINISHING,

    /** The result was received and stored. */
    FINISHED,

    /** The match failed and cannot be resumed. */
    FAILED,

    /** The match was cancelled by an administrator. */
    CANCELLED
}
