package xyz.pyxismc.tournament.common.result;

import java.util.Objects;
import java.util.UUID;

/**
 * Placement of a single team in a match result.
 */
public record TeamResult(UUID teamId, Placement placement) {

    public TeamResult {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(placement, "placement");
    }
}
