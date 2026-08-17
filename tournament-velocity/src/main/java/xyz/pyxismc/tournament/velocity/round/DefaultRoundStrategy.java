package xyz.pyxismc.tournament.velocity.round;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import xyz.pyxismc.tournament.common.model.Group;
import xyz.pyxismc.tournament.common.model.Match;
import xyz.pyxismc.tournament.common.model.Team;
import xyz.pyxismc.tournament.common.result.MatchResult;
import xyz.pyxismc.tournament.common.result.Placement;

/**
 * Default tournament format.
 * <p>
 * Rules:
 * <ul>
 *   <li>Every group is played as exactly one match of 2 to
 *       {@code maxTeamsPerGroup} teams. Teams are split so no match ever
 *       hosts a single team: leftover teams are rebalanced into matches of
 *       two (e.g. 4 teams -> 2x2, 7 teams -> 3+2+2).</li>
 *   <li>Groups are filled in deterministic team-name order and named
 *       A, B, C, ...</li>
 *   <li>The winner of each match qualifies for the next round.</li>
 *   <li>The tournament ends when fewer than 2 teams remain.</li>
 * </ul>
 */
public final class DefaultRoundStrategy implements RoundStrategy {

    @Override
    public List<Group> buildFirstRound(UUID roundId, List<Team> teams, int maxTeamsPerGroup) {
        List<Team> sorted = sortedByName(teams);
        if (sorted.size() < 2) {
            throw new RoundException("At least 2 teams are required to build a round.");
        }
        return chunk(roundId, 1, sorted.stream().map(Team::id).toList(), maxTeamsPerGroup);
    }

    @Override
    public List<Group> buildNextRound(
            UUID roundId,
            int roundNumber,
            List<Match> finishedMatches,
            Map<UUID, MatchResult> results,
            int maxTeamsPerGroup
    ) {
        List<UUID> winners = new ArrayList<>();
        for (Match match : finishedMatches) {
            MatchResult result = results.get(match.id());
            if (result == null) {
                throw new RoundException("No result recorded for match " + match.id() + ".");
            }
            result.results().stream()
                    .filter(teamResult -> teamResult.placement() == Placement.WINNER)
                    .map(teamResult -> teamResult.teamId())
                    .forEach(winners::add);
        }
        if (winners.size() < 2) {
            return List.of();
        }
        return chunk(roundId, roundNumber, winners, maxTeamsPerGroup);
    }

    private static List<Group> chunk(UUID roundId, int roundNumber, List<UUID> teamIds, int maxTeamsPerGroup) {
        if (maxTeamsPerGroup < 2) {
            throw new IllegalArgumentException("maxTeamsPerGroup must be >= 2");
        }
        List<Integer> sizes = new ArrayList<>();
        int remaining = teamIds.size();
        while (remaining >= 2) {
            int size = Math.min(maxTeamsPerGroup, remaining);
            if (remaining - size == 1) {
                size--; // never leave a group of one behind
            }
            if (size < 2) {
                throw new RoundException("Cannot build round " + roundNumber + ": " + teamIds.size()
                        + " qualified teams cannot be split into matches of 2 to " + maxTeamsPerGroup + " teams.");
            }
            sizes.add(size);
            remaining -= size;
        }
        if (sizes.isEmpty()) {
            throw new RoundException("Cannot build round " + roundNumber + ": fewer than 2 teams qualified.");
        }
        List<Group> groups = new ArrayList<>();
        int cursor = 0;
        for (int i = 0; i < sizes.size(); i++) {
            int size = sizes.get(i);
            groups.add(new Group(UUID.randomUUID(), roundId, groupName(i), teamIds.subList(cursor, cursor + size)));
            cursor += size;
        }
        return groups;
    }

    private static String groupName(int index) {
        return Character.toString((char) ('A' + index));
    }

    private static List<Team> sortedByName(List<Team> teams) {
        return teams.stream()
                .sorted(Comparator.comparing(Team::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
}