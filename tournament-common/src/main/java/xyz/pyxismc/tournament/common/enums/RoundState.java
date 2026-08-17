package xyz.pyxismc.tournament.common.enums;

/**
 * Lifecycle state of a round.
 */
public enum RoundState {

    /** The round was created, groups and matches are being built. */
    CREATED,

    /** The round's matches are running. */
    RUNNING,

    /** All matches of the round finished and results were processed. */
    FINISHED
}
