package xyz.pyxismc.tournament.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class StatsTest {

    private static final UUID ID = UUID.randomUUID();

    @Test
    void playerMatchStatsRejectNegativeValues() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerMatchStats(ID, ID, -1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PlayerMatchStats(ID, ID, 0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PlayerMatchStats(ID, ID, 0, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new PlayerMatchStats(ID, ID, 0, 0, 0, -1));
    }

    @Test
    void teamMatchStatsRejectNegativeValues() {
        assertThrows(IllegalArgumentException.class, () -> new TeamMatchStats(ID, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new TeamMatchStats(ID, 0, -1));
    }

    @Test
    void playerStatsEmptyStartsAtZero() {
        PlayerStats stats = PlayerStats.empty(ID);
        assertEquals(ID, stats.playerId());
        assertEquals(0, stats.matches());
        assertEquals(0, stats.wins());
        assertEquals(0, stats.kills());
        assertEquals(0, stats.deaths());
        assertEquals(0, stats.assists());
        assertEquals(0, stats.damage());
    }

    @Test
    void playerStatsRejectNegativeValues() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerStats(ID, -1, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PlayerStats(ID, 0, -1, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PlayerStats(ID, 0, 0, 0, 0, 0, -1));
    }

    @Test
    void teamStatsEmptyStartsAtZero() {
        TeamStats stats = TeamStats.empty(ID);
        assertEquals(ID, stats.teamId());
        assertEquals(0, stats.matches());
        assertEquals(0, stats.wins());
        assertEquals(0, stats.intermediate());
        assertEquals(0, stats.eliminations());
        assertEquals(0, stats.kills());
        assertEquals(0, stats.deaths());
    }

    @Test
    void teamStatsRejectNegativeValues() {
        assertThrows(IllegalArgumentException.class, () -> new TeamStats(ID, -1, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TeamStats(ID, 0, -1, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TeamStats(ID, 0, 0, -1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TeamStats(ID, 0, 0, 0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TeamStats(ID, 0, 0, 0, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new TeamStats(ID, 0, 0, 0, 0, 0, -1));
    }
}
