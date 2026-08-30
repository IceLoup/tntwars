package xyz.pyxismc.tournament.velocity.round;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import xyz.pyxismc.tournament.common.enums.MatchStatus;
import xyz.pyxismc.tournament.common.enums.RoundState;
import xyz.pyxismc.tournament.common.model.Group;
import xyz.pyxismc.tournament.common.model.Match;
import xyz.pyxismc.tournament.common.model.Round;
import xyz.pyxismc.tournament.common.model.Team;
import xyz.pyxismc.tournament.common.result.MatchResult;
import xyz.pyxismc.tournament.common.result.Placement;
import xyz.pyxismc.tournament.velocity.event.MatchCreatedEvent;
import xyz.pyxismc.tournament.velocity.event.MatchFailedEvent;
import xyz.pyxismc.tournament.velocity.event.MatchFinishedEvent;
import xyz.pyxismc.tournament.velocity.event.MatchStartedEvent;
import xyz.pyxismc.tournament.velocity.event.RoundCreatedEvent;
import xyz.pyxismc.tournament.velocity.event.RoundFinishedEvent;
import xyz.pyxismc.tournament.velocity.event.RoundStartedEvent;
import xyz.pyxismc.tournament.velocity.event.TournamentEventBus;
import com.velocitypowered.api.proxy.server.RegisteredServer;

/**
 * In-memory rounds, groups, matches and results.
 * <p>
 * A round contains one group per match; group composition and advancement
 * are delegated to the {@link RoundStrategy}. When all matches of a round
 * are finished, the strategy builds the next round which is started
 * immediately; an empty next round ends the tournament (the single remaining
 * team is the champion). Match results from Paper are untrusted and are
 * validated here before being stored.
 */
public final class RoundManager {

    private final RoundStrategy strategy;
    private final TournamentEventBus eventBus;
    private final int maxTeamsPerGroup;

    private final Map<UUID, Round> rounds = new ConcurrentHashMap<>();
    private final Map<UUID, Group> groups = new ConcurrentHashMap<>();
    private final Map<UUID, Match> matches = new ConcurrentHashMap<>();
    private final Map<UUID, MatchResult> results = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> groupToMatch = new ConcurrentHashMap<>();

    private volatile UUID currentRoundId;
    private volatile UUID championTeamId;

    public RoundManager(RoundStrategy strategy, TournamentEventBus eventBus, int maxTeamsPerGroup) {
        this.strategy = strategy;
        this.eventBus = eventBus;
        this.maxTeamsPerGroup = maxTeamsPerGroup;
    }

    public Optional<Round> getCurrentRound() {
        UUID id = this.currentRoundId;
        return id == null ? Optional.empty() : Optional.ofNullable(this.rounds.get(id));
    }

    /** All rounds of the session, ordered by number. */
    public List<Round> getRounds() {
        return this.rounds.values().stream()
                .sorted(Comparator.comparingInt(Round::number))
                .toList();
    }

    public List<Match> getMatches() {
        return List.copyOf(this.matches.values());
    }

    /**
     * Finds the match ID for a given team.
     * @param teamId the team ID to search for
     * @return the match ID if the team is in a match, null otherwise
     */
    public UUID getMatchIdForTeam(UUID teamId) {
        for (Match match : matches.values()) {
            if (match.teamIds().contains(teamId)) {
                return match.id();
            }
        }
        return null;
    }

    /**
     * Gets the registered server for a given match.
     * @param matchId the match ID
     * @return the registered server if found, null otherwise
     */
    public RegisteredServer getRegisteredServerForMatch(UUID matchId) {
        Optional<Match> matchOpt = getMatch(matchId);
        if (matchOpt.isPresent()) {
            Match match = matchOpt.get();
            // In a real implementation, we would look up the server from the match
            // For now, we'll return null as this would need to be implemented
            // based on how servers are tracked
            return null;
        }
        return null;
    }

    /**
     * The round's matches in group order (A, B, C, ...), so the strategy
     * sees a deterministic sequence of finished matches.
     */
    public List<Match> getMatchesOfRound(UUID roundId) {
        Round round = this.rounds.get(roundId);
        if (round == null) {
            return List.of();
        }
        return round.groups().stream()
                .map(group -> this.matches.get(this.groupToMatch.get(group.id())))
                .filter(Objects::nonNull)
                .toList();
    }

    public Optional<Match> getMatch(UUID matchId) {
        return Optional.ofNullable(this.matches.get(matchId));
    }

    public Optional<MatchResult> getResult(UUID matchId) {
        return Optional.ofNullable(this.results.get(matchId));
    }

    /** Immutable copy of all recorded results, keyed by match id. */
    public Map<UUID, MatchResult> getResults() {
        return Map.copyOf(this.results);
    }

    public Optional<UUID> getChampionTeamId() {
        return Optional.ofNullable(this.championTeamId);
    }

    /** True when the tournament has no current round anymore. */
    public boolean isFinished() {
        return this.currentRoundId == null;
    }

    /** Builds and stores round 1 from all registered teams. */
    public synchronized Round createFirstRound(UUID tournamentId, List<Team> teams) {
        UUID roundId = UUID.randomUUID();
        List<Group> built = this.strategy.buildFirstRound(roundId, teams, this.maxTeamsPerGroup);
        Round round = new Round(roundId, tournamentId, 1, RoundState.CREATED, built);
        storeRound(round);
        return round;
    }

    /** Starts a created round: all its matches move to RUNNING. */
    public synchronized Round startRound(UUID roundId) {
        Round round = requireRound(roundId);
        if (round.state() != RoundState.CREATED) {
            throw new RoundException("Round " + round.number() + " is already " + round.state() + ".");
        }
        this.rounds.put(round.id(), round.withState(RoundState.RUNNING));
        List<Match> roundMatches = getMatchesOfRound(roundId);
        for (Match match : roundMatches) {
            Match started = match.withStatus(MatchStatus.RUNNING);
            this.matches.put(started.id(), started);
            this.eventBus.fire(new MatchStartedEvent(started));
        }
        this.eventBus.fire(new RoundStartedEvent(this.rounds.get(roundId)));
        return this.rounds.get(roundId);
    }

    /**
     * Records a match result. The result is validated (match running,
     * exact team set, single winner, distinct placements). When all matches
     * of the round are finished, the strategy builds the next round which is
     * started immediately; an empty next round ends the tournament.
     *
     * @return the current round after processing, or empty when the
     *         tournament ended with this result
     */
    public synchronized Optional<Round> submitMatchResult(UUID matchId, MatchResult result) {
        Match match = requireMatch(matchId);
        if (match.status() != MatchStatus.RUNNING) {
            throw new RoundException("Match " + matchId + " is not running (status " + match.status() + ").");
        }
        if (!matchId.equals(result.matchId())) {
            throw new RoundException("Result match id does not match the match.");
        }
        if (!result.containsExactlyTeams(match.teamIds())) {
            throw new RoundException("Result does not cover exactly the match's teams.");
        }
        long winners = result.results().stream()
                .filter(teamResult -> teamResult.placement() == Placement.WINNER)
                .count();
        if (winners != 1) {
            throw new RoundException("A match must have exactly one winner (found " + winners + ").");
        }
        long distinct = result.results().stream()
                .map(teamResult -> teamResult.placement())
                .distinct()
                .count();
        if (distinct != result.results().size()) {
            throw new RoundException("Every team must have a distinct placement.");
        }

        this.results.put(matchId, result);
        Match finished = match.withStatus(MatchStatus.FINISHED);
        this.matches.put(matchId, finished);
        this.eventBus.fire(new MatchFinishedEvent(finished, result));

        Round round = requireRound(match.roundId());
        boolean roundComplete = getMatchesOfRound(round.id()).stream()
                .allMatch(candidate -> candidate.status() == MatchStatus.FINISHED);
        if (!roundComplete) {
            return Optional.of(round);
        }

        Round finishedRound = round.withState(RoundState.FINISHED);
        this.rounds.put(finishedRound.id(), finishedRound);
        this.eventBus.fire(new RoundFinishedEvent(finishedRound));

        List<Match> roundMatches = getMatchesOfRound(round.id());
        UUID nextRoundId = UUID.randomUUID();
        List<Group> nextGroups = this.strategy.buildNextRound(
                nextRoundId,
                round.number() + 1,
                roundMatches,
                this.results,
                this.maxTeamsPerGroup);
        if (nextGroups.isEmpty()) {
            this.currentRoundId = null;
            List<UUID> finalWinners = roundMatches.stream()
                    .flatMap(finishedMatch -> this.results.get(finishedMatch.id()).results().stream())
                    .filter(teamResult -> teamResult.placement() == Placement.WINNER)
                    .map(teamResult -> teamResult.teamId())
                    .toList();
            if (finalWinners.size() == 1) {
                this.championTeamId = finalWinners.getFirst();
            }
            return Optional.empty();
        }

        Round nextRound = new Round(nextRoundId,
                round.tournamentId(), round.number() + 1, RoundState.CREATED, nextGroups);
        storeRound(nextRound);
        return Optional.of(startRound(nextRound.id()));
    }

    /** Assigns the provisioned server id to a match. Returns the updated match. */
    public synchronized Match assignServer(UUID matchId, String serverId) {
        Match match = requireMatch(matchId);
        Match updated = match.withServerId(serverId);
        this.matches.put(matchId, updated);
        return updated;
    }

    /** Marks a running match as failed. The round cannot complete normally anymore. */
    public synchronized void failMatch(UUID matchId, String reason) {
        Match match = requireMatch(matchId);
        if (match.status() != MatchStatus.RUNNING) {
            throw new RoundException("Match " + matchId + " is not running (status " + match.status() + ").");
        }
        Match failed = match.withStatus(MatchStatus.FAILED);
        this.matches.put(matchId, failed);
        this.eventBus.fire(new MatchFailedEvent(failed, reason));
    }

    /** Cancels every active match (tournament cancelled). */
    public synchronized void cancelAll() {
        for (Map.Entry<UUID, Match> entry : this.matches.entrySet()) {
            MatchStatus status = entry.getValue().status();
            if (status == MatchStatus.CREATED || status == MatchStatus.RUNNING) {
                this.matches.put(entry.getKey(), entry.getValue().withStatus(MatchStatus.CANCELLED));
            }
        }
        this.currentRoundId = null;
    }

    private void storeRound(Round round) {
        this.rounds.put(round.id(), round);
        for (Group group : round.groups()) {
            this.groups.put(group.id(), group);
            Match match = new Match(
                    UUID.randomUUID(),
                    round.id(),
                    group.teamIds(),
                    null,
                    MatchStatus.CREATED);
            this.matches.put(match.id(), match);
            this.groupToMatch.put(group.id(), match.id());
            this.eventBus.fire(new MatchCreatedEvent(match));
        }
        this.currentRoundId = round.id();
        this.eventBus.fire(new RoundCreatedEvent(round));
    }

    private Round requireRound(UUID roundId) {
        Round round = this.rounds.get(roundId);
        if (round == null) {
            throw new RoundException("Round " + roundId + " does not exist.");
        }
        return round;
    }

    private Match requireMatch(UUID matchId) {
        Match match = this.matches.get(matchId);
        if (match == null) {
            throw new RoundException("Match " + matchId + " does not exist.");
        }
        return match;
    }
}