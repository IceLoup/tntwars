package xyz.pyxismc.tournament.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import xyz.pyxismc.tournament.common.enums.TeamRole;

class TeamTest {

    private static final UUID CAPTAIN = UUID.randomUUID();

    private static Team team() {
        return new Team(
                UUID.randomUUID(),
                "TNT Crew",
                CAPTAIN,
                List.of(new TeamPlayer(CAPTAIN, "Captain", TeamRole.CAPTAIN)),
                false);
    }

    @Test
    void blankNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Team(UUID.randomUUID(), "  ", CAPTAIN, List.of(), false));
    }

    @Test
    void nullCaptainIsRejected() {
        assertThrows(NullPointerException.class, () -> new Team(UUID.randomUUID(), "TNT Crew", null, List.of(), false));
    }

    @Test
    void playersListIsDefensivelyCopied() {
        List<TeamPlayer> mutable = new ArrayList<>();
        mutable.add(new TeamPlayer(CAPTAIN, "Captain", TeamRole.CAPTAIN));
        Team team = new Team(UUID.randomUUID(), "TNT Crew", CAPTAIN, mutable, false);

        mutable.add(new TeamPlayer(UUID.randomUUID(), "Other", TeamRole.MEMBER));

        assertEquals(1, team.players().size());
        assertThrows(UnsupportedOperationException.class, () -> team.players().add(
                new TeamPlayer(UUID.randomUUID(), "Third", TeamRole.MEMBER)));
    }

    @Test
    void withersProduceNewInstances() {
        Team original = team();

        Team locked = original.withLocked(true);
        assertTrue(locked.locked());
        assertFalse(original.locked());
        assertNotSame(original, locked);
        assertEquals(original.id(), locked.id());
        assertEquals(original.players(), locked.players());

        Team renamed = original.withName("Other Name");
        assertEquals("Other Name", renamed.name());
        assertEquals(original.id(), renamed.id());

        Team withoutMembers = original.withPlayers(List.of());
        assertTrue(withoutMembers.players().isEmpty());
        assertEquals(original.id(), withoutMembers.id());
    }

    @Test
    void recordEqualityUsesAllComponents() {
        Team a = team();
        Team b = new Team(
                a.id(), a.name(), a.captainId(), a.players(), a.locked());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
