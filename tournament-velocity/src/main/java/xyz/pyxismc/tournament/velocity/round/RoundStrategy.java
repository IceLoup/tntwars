package xyz.pyxismc.tournament.velocity.round;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import xyz.pyxismc.tournament.common.model.Group;
import xyz.pyxismc.tournament.common.model.Match;
import xyz.pyxismc.tournament.common.model.Team;
import xyz.pyxismc.tournament.common.result.MatchResult;

/**
 * Decides how a tournament is structured: group composition for every round
 * and which teams qualify for the next round. The tournament format is
 * entirely pluggable; managers never hardcode formats or team counts.
 */
public interface RoundStrategy {

    /**
     * Builds the groups of the first round from all registered teams.
     * Every group will be played as exactly one match.
     *
     * @param roundId           the round the groups belong to
     * @param teams             all registered teams
     * @param maxTeamsPerGroup  maximum teams per group (= per match server)
     * @return the groups, never empty
     * @throws RoundException when the teams cannot form a valid first round
     */
    List<Group> buildFirstRound(UUID roundId, List<Team> teams, int maxTeamsPerGroup);

    /**
     * Builds the groups of the next round from the finished round's matches
     * and their results. An empty result means the tournament is over.
     *
     * @param roundId           the next round's id
     * @param roundNumber       the next round's number (>= 2)
     * @param finishedMatches   the finished round's matches, in group order
     * @param results           match results of the finished round, keyed by match id
     * @param maxTeamsPerGroup  maximum teams per group
     * @return the next round's groups, or an empty list when the tournament ends
     * @throws RoundException when the qualified teams cannot form a valid round
     */
    List<Group> buildNextRound(
            UUID roundId,
            int roundNumber,
            List<Match> finishedMatches,
            Map<UUID, MatchResult> results,
            int maxTeamsPerGroup
    );
}