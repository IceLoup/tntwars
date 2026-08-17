package xyz.pyxismc.tournament.paper.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import xyz.pyxismc.tournament.common.result.MatchResult;
import xyz.pyxismc.tournament.common.result.Placement;

class MatchSessionTest {

    private static UUID team(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes());
    }

    private static UUID player(String seed) {
        return UUID.nameUUIDFromBytes(("p-" + seed).getBytes());
    }

    /** Two teams of two players each. */
    private static MatchSession twoTeamSession() {
        UUID teamA = team("A");
        UUID teamB = team("B");
        Map<UUID, UUID> players = new LinkedHashMap<>();
        players.put(player("a1"), teamA);
        players.put(player("a2"), teamA);
        players.put(player("b1"), teamB);
        players.put(player("b2"), teamB);
        return new MatchSession(UUID.randomUUID(), "game-1", players, Instant.now());
    }

    /** Three teams of two players each. */
    private static MatchSession threeTeamSession() {
        UUID teamA = team("A");
        UUID teamB = team("B");
        UUID teamC = team("C");
        Map<UUID, UUID> players = new LinkedHashMap<>();
        players.put(player("a1"), teamA);
        players.put(player("a2"), teamA);
        players.put(player("b1"), teamB);
        players.put(player("b2"), teamB);
        players.put(player("c1"), teamC);
        players.put(player("c2"), teamC);
        return new MatchSession(UUID.randomUUID(), "game-1", players, Instant.now());
    }

    @Test
    void constructorRejectsSingleTeam() {
        Map<UUID, UUID> players = Map.of(player("a1"), team("A"));
        try {
            new MatchSession(UUID.randomUUID(), "game-1", players, Instant.now());
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    void twoTeamMatchEndsWhenOneTeamIsEliminated() {
        MatchSession session = twoTeamSession();
        UUID teamA = team("A");
        UUID teamB = team("B");

        session.onPlayerDeath(player("a1"), player("b1"));
        assertFalse(session.isOver());
        session.onPlayerDeath(player("a2"), player("b2"));
        assertTrue(session.isOver());
        assertEquals(teamB, session.winnerTeamId().orElseThrow());

        MatchResult result = session.buildResult(UUID.randomUUID());
        assertEquals(2, result.results().size());
        assertEquals(Placement.WINNER, result.results().get(0).placement());
        assertEquals(Placement.ELIMINATED, result.results().get(1).placement());
    }

    @Test
    void threeTeamMatchAwardsIntermediatePlacement() {
        MatchSession session = threeTeamSession();

        session.onPlayerDeath(player("a1"), player("b1"));
        session.onPlayerDeath(player("a2"), player("b2"));
        session.onPlayerDeath(player("b1"), player("c1"));
        session.onPlayerDeath(player("b2"), player("c2"));
        assertTrue(session.isOver());
        assertEquals(team("C"), session.winnerTeamId().orElseThrow());

        MatchResult result = session.buildResult(UUID.randomUUID());
        Map<UUID, Placement> placements = new LinkedHashMap<>();
        for (var teamResult : result.results()) {
            placements.put(teamResult.teamId(), teamResult.placement());
        }
        assertEquals(Placement.WINNER, placements.get(team("C")));
        assertEquals(Placement.INTERMEDIATE, placements.get(team("B")));
        assertEquals(Placement.ELIMINATED, placements.get(team("A")));
    }

    @Test
    void quitCountsAsElimination() {
        MatchSession session = twoTeamSession();

        session.onPlayerQuit(player("a1"));
        session.onPlayerDeath(player("a2"), player("b1"));
        assertTrue(session.isOver());
        assertEquals(team("B"), session.winnerTeamId().orElseThrow());
    }

    @Test
    void killAndDamageStatsAreTracked() {
        MatchSession session = twoTeamSession();
        UUID teamA = team("A");

        session.recordDamage(player("a1"), 7.5);
        session.recordAttacker(player("a1"), player("b1"));
        session.onPlayerDeath(player("a1"), player("b1"));
        session.onPlayerDeath(player("a2"), player("b2"));

        MatchResult result = session.buildResult(UUID.randomUUID());
        assertEquals(1, result.playerStats().get(player("b1")).kills());
        assertEquals(1, result.playerStats().get(player("a1")).deaths());
        assertEquals(8, result.playerStats().get(player("a1")).damage());
        assertEquals(2, result.teamStats().get(teamA).deaths());
        assertEquals(2, result.teamStats().get(team("B")).kills());
    }

    @Test
    void timeoutPicksWinnerByKills() {
        MatchSession session = twoTeamSession();
        UUID teamA = team("A");
        UUID teamB = team("B");

        session.onPlayerDeath(player("a1"), player("b1"));
        session.onPlayerDeath(player("a2"), player("b2"));
        session.onPlayerDeath(player("b2"), player("a1"));
        // b2 died with no further fights: kills A=1 (b2 by a1), B=2
        session.finishByTimeout();

        assertEquals(teamB, session.winnerTeamId().orElseThrow());
        MatchResult result = session.buildResult(UUID.randomUUID());
        assertEquals(Placement.WINNER, result.results().get(0).placement());
    }

    @Test
    void noEliminationTimeoutStillProducesDistinctPlacements() {
        MatchSession session = threeTeamSession();
        UUID teamA = team("A");

        session.onPlayerDeath(player("a2"), player("b1"));
        session.finishByTimeout();
        assertTrue(session.isOver());

        MatchResult result = session.buildResult(UUID.randomUUID());
        assertEquals(3, result.results().size());
        assertEquals(3, result.results().stream().map(r -> r.placement()).distinct().count());
        assertEquals(1, result.results().stream().filter(r -> r.placement() == Placement.WINNER).count());
        assertTrue(result.results().stream().anyMatch(r -> r.teamId().equals(teamA)));
    }

    @Test
    void resultCoversEveryPlayer() {
        MatchSession session = threeTeamSession();
        session.onPlayerDeath(player("a1"), player("b1"));
        session.onPlayerDeath(player("a2"), player("b2"));
        session.onPlayerDeath(player("b1"), player("c1"));
        session.onPlayerDeath(player("b2"), player("c2"));

        MatchResult result = session.buildResult(UUID.randomUUID());
        assertEquals(6, result.playerStats().size());
        assertTrue(result.results().stream().allMatch(r -> result.teamStats().containsKey(r.teamId())));
    }
}