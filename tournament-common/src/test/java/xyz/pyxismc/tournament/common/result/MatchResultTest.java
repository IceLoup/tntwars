package xyz.pyxismc.tournament.common.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class MatchResultTest {

    private static final UUID MATCH = UUID.randomUUID();
    private static final UUID TEAM_A = UUID.randomUUID();
    private static final UUID TEAM_B = UUID.randomUUID();
    private static final UUID TEAM_C = UUID.randomUUID();

    private static MatchResult threeTeams() {
        return new MatchResult(
                MATCH,
                List.of(
                        new TeamResult(TEAM_A, Placement.WINNER),
                        new TeamResult(TEAM_B, Placement.INTERMEDIATE),
                        new TeamResult(TEAM_C, Placement.ELIMINATED)),
                Map.of(),
                Map.of(),
                Duration.ofMinutes(12),
                Instant.now());
    }

    @Test
    void resultOfReturnsPlacementOfTeam() {
        MatchResult result = threeTeams();

        assertEquals(Placement.WINNER, result.resultOf(TEAM_A).orElseThrow().placement());
        assertEquals(Placement.INTERMEDIATE, result.resultOf(TEAM_B).orElseThrow().placement());
        assertEquals(Placement.ELIMINATED, result.resultOf(TEAM_C).orElseThrow().placement());
        assertTrue(result.resultOf(UUID.randomUUID()).isEmpty());
    }

    @Test
    void hasResultForOnlyReturnsTrueForSubmittedTeams() {
        MatchResult result = threeTeams();
        assertTrue(result.hasResultFor(TEAM_A));
        assertTrue(result.hasResultFor(TEAM_C));
        assertFalse(result.hasResultFor(UUID.randomUUID()));
    }

    @Test
    void containsExactlyTeamsAcceptsTheExactSet() {
        MatchResult result = threeTeams();
        assertTrue(result.containsExactlyTeams(List.of(TEAM_A, TEAM_B, TEAM_C)));
    }

    @Test
    void containsExactlyTeamsRejectsMissingTeam() {
        MatchResult result = threeTeams();
        assertFalse(result.containsExactlyTeams(List.of(TEAM_A, TEAM_B)));
    }

    @Test
    void containsExactlyTeamsRejectsUnknownTeam() {
        MatchResult result = threeTeams();
        assertFalse(result.containsExactlyTeams(List.of(TEAM_A, TEAM_B, UUID.randomUUID())));
    }

    @Test
    void containsExactlyTeamsRejectsDuplicateTeam() {
        MatchResult result = new MatchResult(
                MATCH,
                List.of(
                        new TeamResult(TEAM_A, Placement.WINNER),
                        new TeamResult(TEAM_A, Placement.INTERMEDIATE),
                        new TeamResult(TEAM_C, Placement.ELIMINATED)),
                Map.of(),
                Map.of(),
                Duration.ZERO,
                Instant.now());

        assertFalse(result.containsExactlyTeams(List.of(TEAM_A, TEAM_B, TEAM_C)));
    }

    @Test
    void containsExactlyTeamsRejectsSameSizeButDifferentTeams() {
        MatchResult result = threeTeams();
        assertFalse(result.containsExactlyTeams(List.of(TEAM_A, TEAM_B, UUID.randomUUID())));
    }

    @Test
    void resultsListIsDefensivelyCopied() {
        List<TeamResult> mutable = new java.util.ArrayList<>();
        mutable.add(new TeamResult(TEAM_A, Placement.WINNER));
        MatchResult result = new MatchResult(
                MATCH, mutable, Map.of(), Map.of(), Duration.ZERO, Instant.now());

        mutable.add(new TeamResult(TEAM_B, Placement.INTERMEDIATE));

        assertEquals(1, result.results().size());
        assertThrows(UnsupportedOperationException.class,
                () -> result.results().add(new TeamResult(TEAM_B, Placement.INTERMEDIATE)));
    }

    @Test
    void requiredFieldsAreEnforced() {
        assertThrows(NullPointerException.class, () -> new MatchResult(
                null, List.of(), Map.of(), Map.of(), Duration.ZERO, Instant.now()));
        assertThrows(NullPointerException.class, () -> new MatchResult(
                MATCH, List.of(), Map.of(), Map.of(), null, Instant.now()));
        assertThrows(NullPointerException.class, () -> new MatchResult(
                MATCH, List.of(), Map.of(), Map.of(), Duration.ZERO, null));
        assertThrows(NullPointerException.class, () -> new MatchResult(
                MATCH, List.of(new TeamResult(TEAM_A, null)), Map.of(), Map.of(), Duration.ZERO, Instant.now()));
    }
}
