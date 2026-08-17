package xyz.pyxismc.tournament.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import xyz.pyxismc.tournament.common.enums.RoundState;
import xyz.pyxismc.tournament.common.enums.TournamentState;

class TournamentTest {

    private static Tournament tournament() {
        return new Tournament(
                UUID.randomUUID(),
                "Summer Cup",
                TournamentState.REGISTRATION,
                List.of(UUID.randomUUID(), UUID.randomUUID()),
                List.of(),
                Instant.now(),
                null,
                null);
    }

    @Test
    void stateWitherPreservesOtherFields() {
        Tournament original = tournament();

        Tournament started = original.withState(TournamentState.STARTING);

        assertEquals(TournamentState.STARTING, started.state());
        assertEquals(original.id(), started.id());
        assertEquals(original.name(), started.name());
        assertEquals(original.teamIds(), started.teamIds());
        assertEquals(original.roundIds(), started.roundIds());
    }

    @Test
    void teamIdsAreDefensivelyCopied() {
        List<UUID> mutable = new ArrayList<>(List.of(UUID.randomUUID()));
        Tournament tournament = new Tournament(
                UUID.randomUUID(), "Summer Cup", TournamentState.REGISTRATION,
                mutable, List.of(), Instant.now(), null, null);

        mutable.add(UUID.randomUUID());

        assertEquals(1, tournament.teamIds().size());
        assertThrows(UnsupportedOperationException.class, () -> tournament.teamIds().add(UUID.randomUUID()));
    }

    @Test
    void invalidInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Tournament(
                UUID.randomUUID(), "  ", TournamentState.REGISTRATION,
                List.of(), List.of(), Instant.now(), null, null));
        assertThrows(NullPointerException.class, () -> new Tournament(
                null, "Summer Cup", TournamentState.REGISTRATION,
                List.of(), List.of(), Instant.now(), null, null));
        assertThrows(NullPointerException.class, () -> new Tournament(
                UUID.randomUUID(), "Summer Cup", null,
                List.of(), List.of(), Instant.now(), null, null));
        assertThrows(NullPointerException.class, () -> new Tournament(
                UUID.randomUUID(), "Summer Cup", TournamentState.REGISTRATION,
                List.of(), List.of(), null, null, null));
    }

    @Test
    void roundNumberMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new Round(
                UUID.randomUUID(), UUID.randomUUID(), 0, RoundState.CREATED, List.of()));
    }

    @Test
    void roundWitherKeepsNumberAndGroups() {
        UUID roundId = UUID.randomUUID();
        Round round = new Round(roundId, UUID.randomUUID(), 1, RoundState.CREATED, List.of());
        Round running = round.withState(RoundState.RUNNING);

        assertTrue(running.groups().isEmpty());
        assertEquals(roundId, running.id());
        assertEquals(1, running.number());
    }
}
