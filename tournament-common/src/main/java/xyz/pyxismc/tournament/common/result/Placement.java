package xyz.pyxismc.tournament.common.result;

/**
 * Placement of a team in a match. There is no numeric rating or Elo:
 * the result of a match is only one of these three placements.
 */
public enum Placement {

    /** 1st place. */
    WINNER(1),

    /** 2nd place. */
    INTERMEDIATE(2),

    /** 3rd place. */
    ELIMINATED(3);

    private final int rank;

    Placement(int rank) {
        this.rank = rank;
    }

    /**
     * Position of the placement: 1 = WINNER, 2 = INTERMEDIATE, 3 = ELIMINATED.
     */
    public int rank() {
        return this.rank;
    }

    public boolean ranksHigherThan(Placement other) {
        return this.rank < other.rank;
    }

    public boolean ranksLowerThan(Placement other) {
        return this.rank > other.rank;
    }
}
