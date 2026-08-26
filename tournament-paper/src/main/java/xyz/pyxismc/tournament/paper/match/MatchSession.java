package xyz.pyxismc.tournament.paper.match;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import xyz.pyxismc.tournament.common.model.PlayerMatchStats;
import xyz.pyxismc.tournament.common.model.TeamMatchStats;
import xyz.pyxismc.tournament.common.result.MatchResult;
import xyz.pyxismc.tournament.common.result.Placement;
import xyz.pyxismc.tournament.common.result.TeamResult;

/**
 * Pure match state of one server: teams, alive members, eliminations and
 * statistics. No Bukkit types: fully unit-testable.
 *
 * <p>The last team with at least one alive member wins. On timeout, the
 * winner is the team with the most kills (then most alive members, then
 * lowest team id as a deterministic tie-break).</p>
 */
public final class MatchSession {

    /** Per-player counters. */
    private static final class Stats {
        private final UUID playerId;
        private final UUID teamId;
        private long kills;
        private long deaths;
        private long damage;
        private long assists;

        private Stats(UUID playerId, UUID teamId) {
            this.playerId = playerId;
            this.teamId = teamId;
        }
    }

    private final UUID matchId;
    private final String serverId;
    private final Instant startedAt;
    private final Map<UUID, UUID> playerToTeam;
    private final Map<UUID, Integer> aliveByTeam;
    private final List<UUID> teamOrder;
    private final List<UUID> eliminationOrder = new ArrayList<>();
    private final Map<UUID, Stats> stats = new LinkedHashMap<>();
    private final Map<UUID, UUID> lastDamager = new HashMap<>();
    private final Map<UUID, Long> teamKills = new HashMap<>();
    private final Set<UUID> eliminated = new HashSet<>();

    private UUID winnerTeamId;

    /**
     * @param playerToTeam every match player mapped to its team; at least two
     *                     distinct teams must be present
     */
    public MatchSession(UUID matchId, String serverId, Map<UUID, UUID> playerToTeam, Instant startedAt) {
        this.matchId = Objects.requireNonNull(matchId, "matchId");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        Map<UUID, UUID> copy = new LinkedHashMap<>(playerToTeam);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("a match needs at least one player");
        }
        if (copy.values().stream().distinct().count() < 2) {
            throw new IllegalArgumentException("a match needs at least two teams");
        }
        this.playerToTeam = Map.copyOf(copy);
        Map<UUID, Integer> alive = new LinkedHashMap<>();
        for (UUID teamId : this.playerToTeam.values()) {
            alive.merge(teamId, 1, Integer::sum);
        }
        this.aliveByTeam = alive;
        this.teamOrder = List.copyOf(alive.keySet());
        for (Map.Entry<UUID, UUID> entry : this.playerToTeam.entrySet()) {
            this.stats.put(entry.getKey(), new Stats(entry.getKey(), entry.getValue()));
        }
        for (UUID teamId : this.teamOrder) {
            this.teamKills.put(teamId, 0L);
        }
    }

    public UUID matchId() {
        return this.matchId;
    }

    public String serverId() {
        return this.serverId;
    }

    public Instant startedAt() {
        return this.startedAt;
    }

    public UUID teamOf(UUID playerId) {
        return this.playerToTeam.get(playerId);
    }

    public boolean isMatchPlayer(UUID playerId) {
        return this.playerToTeam.containsKey(playerId);
    }

    /** Team ids in roster order. */
    public List<UUID> teamIds() {
        return this.teamOrder;
    }

    /** Total roster size of a team (alive or not). */
    public int teamSize(UUID teamId) {
        int size = 0;
        for (UUID memberTeam : this.playerToTeam.values()) {
            if (memberTeam.equals(teamId)) {
                size++;
            }
        }
        return size;
    }

    public int aliveCount(UUID teamId) {
        return this.aliveByTeam.getOrDefault(teamId, 0);
    }

    public int rosterSize() {
        return this.playerToTeam.size();
    }

    /** True once the player has died or quit during the match. */
    public boolean isEliminated(UUID playerId) {
        return this.eliminated.contains(playerId);
    }

    /** Kills credited to a player so far. */
    public long killsOf(UUID playerId) {
        Stats stats = this.stats.get(playerId);
        return stats == null ? 0 : stats.kills;
    }

    /** True when exactly one team still has alive members. */
    public boolean isOver() {
        return this.winnerTeamId != null;
    }

    public Optional<UUID> winnerTeamId() {
        return Optional.ofNullable(this.winnerTeamId);
    }

    /** Records damage dealt to a match player. */
    public void recordDamage(UUID victimId, double amount) {
        Stats victim = this.stats.get(victimId);
        if (victim != null && amount > 0) {
            victim.damage += Math.round(amount);
        }
    }

    /** Records the last player to damage the victim (kill attribution). */
    public void recordAttacker(UUID victimId, UUID attackerId) {
        if (isMatchPlayer(victimId) && isMatchPlayer(attackerId) && !victimId.equals(attackerId)) {
            this.lastDamager.put(victimId, attackerId);
        }
    }

    /**
     * A player died: the kill goes to the last recorded attacker (if any,
     * fallback to {@code killerId}).
     */
    public void onPlayerDeath(UUID playerId, UUID killerId) {
        Stats victim = this.stats.get(playerId);
        if (victim == null) {
            return;
        }
        victim.deaths++;
        UUID attacker = this.lastDamager.remove(playerId);
        if (attacker == null) {
            attacker = killerId;
        }
        if (attacker != null && isMatchPlayer(attacker) && !attacker.equals(playerId)) {
            Stats killer = this.stats.get(attacker);
            killer.kills++;
            this.teamKills.merge(killer.teamId, 1L, Long::sum);
        }
        eliminateMember(playerId);
    }

    /** A player left the server: its member slot is lost (no death stat). */
    public void onPlayerQuit(UUID playerId) {
        if (this.stats.containsKey(playerId)) {
            this.lastDamager.remove(playerId);
            eliminateMember(playerId);
        }
    }

    /** Ends the match by timeout: the best team wins, others ranked by kills. */
    public void finishByTimeout() {
        if (isOver()) {
            return;
        }
        this.winnerTeamId = bestTeam();
    }

    /**
     * Builds the final result. Must only be called once the match is over
     * (naturally or by timeout).
     */
    public MatchResult buildResult(UUID matchId) {
        if (!isOver()) {
            finishByTimeout();
        }
        List<UUID> ranked = rankedTeams();
        List<TeamResult> results = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            results.add(new TeamResult(ranked.get(i), placementFor(i, ranked.size())));
        }
        Map<UUID, TeamMatchStats> teamStats = new LinkedHashMap<>();
        for (UUID teamId : this.teamOrder) {
            long kills = this.teamKills.getOrDefault(teamId, 0L);
            long deaths = this.stats.values().stream()
                    .filter(stats -> stats.teamId.equals(teamId))
                    .mapToLong(stats -> stats.deaths)
                    .sum();
            teamStats.put(teamId, new TeamMatchStats(teamId, kills, deaths));
        }
        Map<UUID, PlayerMatchStats> playerStats = new LinkedHashMap<>();
        for (Stats stats : this.stats.values()) {
            playerStats.put(stats.playerId, new PlayerMatchStats(
                    stats.playerId, stats.teamId, stats.kills, stats.deaths, stats.assists, stats.damage));
        }
        return new MatchResult(
                matchId,
                results,
                teamStats,
                playerStats,
                Duration.between(this.startedAt, Instant.now()),
                Instant.now());
    }

    private void eliminateMember(UUID playerId) {
        UUID teamId = this.playerToTeam.get(playerId);
        if (teamId == null) {
            return;
        }
        int remaining = this.aliveByTeam.getOrDefault(teamId, 0);
        if (remaining <= 0) {
            return;
        }
        this.aliveByTeam.put(teamId, remaining - 1);
        if (remaining - 1 == 0) {
            this.eliminationOrder.add(teamId);
            List<UUID> aliveTeams = this.aliveByTeam.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .toList();
            if (aliveTeams.size() == 1) {
                this.winnerTeamId = aliveTeams.getFirst();
            }
        }
    }

    private UUID bestTeam() {
        return this.teamOrder.stream()
                .max(Comparator
                        .comparingLong((UUID teamId) -> this.teamKills.getOrDefault(teamId, 0L))
                        .thenComparingInt(this.aliveByTeam::get)
                        .thenComparing(Comparator.reverseOrder()))
                .orElseThrow();
    }

    /** Winner first, then teams by elimination order (last out = best of the rest). */
    private List<UUID> rankedTeams() {
        List<UUID> ranked = new ArrayList<>();
        ranked.add(this.winnerTeamId);
        for (int i = this.eliminationOrder.size() - 1; i >= 0; i--) {
            ranked.add(this.eliminationOrder.get(i));
        }
        for (UUID teamId : this.teamOrder) {
            if (!ranked.contains(teamId)) {
                ranked.add(teamId);
            }
        }
        return ranked;
    }

    /** Only 3-team matches have an INTERMEDIATE placement (second place). */
    private static Placement placementFor(int rank, int teamCount) {
        if (rank == 0) {
            return Placement.WINNER;
        }
        return teamCount == 3 && rank == 1 ? Placement.INTERMEDIATE : Placement.ELIMINATED;
    }
}