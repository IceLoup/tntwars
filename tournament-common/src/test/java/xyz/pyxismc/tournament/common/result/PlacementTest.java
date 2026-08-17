package xyz.pyxismc.tournament.common.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlacementTest {

    @Test
    void rankFollowsWinnerIntermediateEliminatedOrder() {
        assertEquals(1, Placement.WINNER.rank());
        assertEquals(2, Placement.INTERMEDIATE.rank());
        assertEquals(3, Placement.ELIMINATED.rank());
    }

    @Test
    void winnerRanksHigherThanEveryOtherPlacement() {
        assertTrue(Placement.WINNER.ranksHigherThan(Placement.INTERMEDIATE));
        assertTrue(Placement.WINNER.ranksHigherThan(Placement.ELIMINATED));
        assertFalse(Placement.WINNER.ranksHigherThan(Placement.WINNER));
    }

    @Test
    void intermediateRanksBetweenWinnerAndEliminated() {
        assertTrue(Placement.INTERMEDIATE.ranksHigherThan(Placement.ELIMINATED));
        assertTrue(Placement.INTERMEDIATE.ranksLowerThan(Placement.WINNER));
        assertTrue(Placement.ELIMINATED.ranksLowerThan(Placement.INTERMEDIATE));
    }

    @Test
    void rankOrderMatchesSpecRuleWinnerGtIntermediateGtEliminated() {
        assertTrue(Placement.WINNER.rank() < Placement.INTERMEDIATE.rank());
        assertTrue(Placement.INTERMEDIATE.rank() < Placement.ELIMINATED.rank());
    }
}
