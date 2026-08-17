package xyz.pyxismc.tournament.velocity.tournament;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import xyz.pyxismc.tournament.common.enums.TournamentState;

class TournamentStateMachineTest {

    private final TournamentStateMachine machine = new TournamentStateMachine();

    @Test
    void registrationCanStartOrCancel() {
        assertTrue(machine.canTransition(TournamentState.REGISTRATION, TournamentState.STARTING));
        assertTrue(machine.canTransition(TournamentState.REGISTRATION, TournamentState.CANCELLED));
        assertFalse(machine.canTransition(TournamentState.REGISTRATION, TournamentState.ROUND_RUNNING));
        assertFalse(machine.canTransition(TournamentState.REGISTRATION, TournamentState.FINISHED));
        assertFalse(machine.canTransition(TournamentState.REGISTRATION, TournamentState.PAUSED));
    }

    @Test
    void startingLeadsToRunningPausedOrCancelled() {
        assertTrue(machine.canTransition(TournamentState.STARTING, TournamentState.ROUND_RUNNING));
        assertTrue(machine.canTransition(TournamentState.STARTING, TournamentState.PAUSED));
        assertTrue(machine.canTransition(TournamentState.STARTING, TournamentState.CANCELLED));
        assertFalse(machine.canTransition(TournamentState.STARTING, TournamentState.FINISHED));
    }

    @Test
    void runningRoundFinishesPausesOrCancels() {
        assertTrue(machine.canTransition(TournamentState.ROUND_RUNNING, TournamentState.ROUND_FINISHED));
        assertTrue(machine.canTransition(TournamentState.ROUND_RUNNING, TournamentState.PAUSED));
        assertTrue(machine.canTransition(TournamentState.ROUND_RUNNING, TournamentState.CANCELLED));
        assertFalse(machine.canTransition(TournamentState.ROUND_RUNNING, TournamentState.FINISHED));
    }

    @Test
    void finishedRoundLeadsToNextRoundFinishOrPause() {
        assertTrue(machine.canTransition(TournamentState.ROUND_FINISHED, TournamentState.ROUND_RUNNING));
        assertTrue(machine.canTransition(TournamentState.ROUND_FINISHED, TournamentState.FINISHED));
        assertTrue(machine.canTransition(TournamentState.ROUND_FINISHED, TournamentState.PAUSED));
        assertTrue(machine.canTransition(TournamentState.ROUND_FINISHED, TournamentState.CANCELLED));
        assertFalse(machine.canTransition(TournamentState.ROUND_FINISHED, TournamentState.STARTING));
    }

    @Test
    void pausedResumesToPausedFromOrCancels() {
        assertTrue(machine.canTransition(TournamentState.PAUSED, TournamentState.STARTING));
        assertTrue(machine.canTransition(TournamentState.PAUSED, TournamentState.ROUND_RUNNING));
        assertTrue(machine.canTransition(TournamentState.PAUSED, TournamentState.ROUND_FINISHED));
        assertTrue(machine.canTransition(TournamentState.PAUSED, TournamentState.CANCELLED));
        assertFalse(machine.canTransition(TournamentState.PAUSED, TournamentState.REGISTRATION));
    }

    @Test
    void terminalStatesHaveNoExit() {
        assertFalse(machine.canTransition(TournamentState.FINISHED, TournamentState.REGISTRATION));
        assertFalse(machine.canTransition(TournamentState.FINISHED, TournamentState.CANCELLED));
        assertFalse(machine.canTransition(TournamentState.CANCELLED, TournamentState.REGISTRATION));
        assertFalse(machine.canTransition(TournamentState.CANCELLED, TournamentState.STARTING));
        assertTrue(machine.isTerminal(TournamentState.FINISHED));
        assertTrue(machine.isTerminal(TournamentState.CANCELLED));
    }

    @Test
    void everyOtherTransitionIsRejected() {
        for (TournamentState from : TournamentState.values()) {
            for (TournamentState to : TournamentState.values()) {
                if (machine.canTransition(from, to)) {
                    continue;
                }
                assertThrows(IllegalStateException.class, () -> machine.requireTransition(from, to),
                        from + " -> " + to + " must be rejected");
            }
        }
    }

    @Test
    void requireTransitionReturnsTargetForValidTransitions() {
        assertTrue(machine.requireTransition(TournamentState.REGISTRATION, TournamentState.STARTING)
                == TournamentState.STARTING);
        assertTrue(machine.requireTransition(TournamentState.ROUND_FINISHED, TournamentState.FINISHED)
                == TournamentState.FINISHED);
    }
}
