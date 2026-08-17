package xyz.pyxismc.tournament.velocity.tournament;

import java.util.Map;
import java.util.Set;

import xyz.pyxismc.tournament.common.enums.TournamentState;

/**
 * Centralized tournament state machine (spec: state changes must never be
 * applied directly from arbitrary classes).
 *
 * <pre>
 * REGISTRATION -> STARTING -> ROUND_RUNNING -> ROUND_FINISHED -> ...
 *      |             |             |                |        |-> FINISHED
 *      |             |             |                |-> PAUSED
 *      |             |             |-> PAUSED       |
 *      |             |-> PAUSED    |                v
 *      |-> CANCELLED  |            v               CANCELLED
 *      |              v           CANCELLED
 *      |            CANCELLED
 *
 * PAUSED -> (state it was paused from) | CANCELLED
 * </pre>
 */
public final class TournamentStateMachine {

    private static final Map<TournamentState, Set<TournamentState>> TRANSITIONS = Map.ofEntries(
            Map.entry(TournamentState.REGISTRATION, Set.of(TournamentState.STARTING, TournamentState.CANCELLED)),
            Map.entry(TournamentState.STARTING, Set.of(TournamentState.ROUND_RUNNING, TournamentState.PAUSED, TournamentState.CANCELLED)),
            Map.entry(TournamentState.ROUND_RUNNING, Set.of(TournamentState.ROUND_FINISHED, TournamentState.PAUSED, TournamentState.CANCELLED)),
            Map.entry(TournamentState.ROUND_FINISHED, Set.of(TournamentState.ROUND_RUNNING, TournamentState.FINISHED, TournamentState.PAUSED, TournamentState.CANCELLED)),
            Map.entry(TournamentState.PAUSED, Set.of(TournamentState.STARTING, TournamentState.ROUND_RUNNING, TournamentState.ROUND_FINISHED, TournamentState.CANCELLED)),
            Map.entry(TournamentState.FINISHED, Set.of()),
            Map.entry(TournamentState.CANCELLED, Set.of()));

    /** True if the transition is allowed. */
    public boolean canTransition(TournamentState from, TournamentState to) {
        return TRANSITIONS.get(from).contains(to);
    }

    /**
     * Returns {@code to} if the transition is allowed, otherwise throws.
     * This is the only way managers apply a state change.
     */
    public TournamentState requireTransition(TournamentState from, TournamentState to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("Illegal tournament state transition: " + from + " -> " + to);
        }
        return to;
    }

    /** Terminal states: no transition leads out of them. */
    public boolean isTerminal(TournamentState state) {
        return state == TournamentState.FINISHED || state == TournamentState.CANCELLED;
    }
}
