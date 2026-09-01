package xyz.pyxismc.tournament.velocity.team;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import xyz.pyxismc.tournament.common.enums.TeamRole;
import xyz.pyxismc.tournament.common.model.Player;
import xyz.pyxismc.tournament.common.model.Team;
import xyz.pyxismc.tournament.common.model.TeamPlayer;
import xyz.pyxismc.tournament.velocity.config.TournamentConfig;
import xyz.pyxismc.tournament.velocity.event.TeamCreatedEvent;
import xyz.pyxismc.tournament.velocity.event.TeamDisbandedEvent;
import xyz.pyxismc.tournament.velocity.event.TeamInviteEvent;
import xyz.pyxismc.tournament.velocity.event.TeamJoinEvent;
import xyz.pyxismc.tournament.velocity.event.TeamLeaveEvent;
import xyz.pyxismc.tournament.velocity.event.TournamentEventBus;

/**
 * Manages teams: creation, invitations, membership and locking.
 * <p>
 * Thread-safe: teams are immutable records stored in concurrent maps; all
 * mutating operations are synchronized. The team model never stores the
 * validation rules; the max team size comes from the configuration.
 */
public final class TeamManager {

    public static final int MAX_TEAM_NAME_LENGTH = 32;

    private final TournamentConfig config;
    private final TournamentEventBus eventBus;

    private final Map<UUID, Team> teams = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerToTeam = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> pendingInvites = new ConcurrentHashMap<>();

    public TeamManager(TournamentConfig config, TournamentEventBus eventBus) {
        this.config = config;
        this.eventBus = eventBus;
    }

    public Optional<Team> getTeam(UUID teamId) {
        return Optional.ofNullable(this.teams.get(teamId));
    }

    public Optional<Team> teamOfPlayer(UUID playerId) {
        UUID teamId = this.playerToTeam.get(playerId);
        return teamId == null ? Optional.empty() : Optional.ofNullable(this.teams.get(teamId));
    }

    /** Immutable snapshot of all registered teams. */
    public List<Team> getTeams() {
        return List.copyOf(this.teams.values());
    }

    /** Returns the team that invited this player, if any. */
    public Optional<UUID> pendingInvitationOf(UUID playerId) {
        return this.pendingInvites.entrySet().stream()
                .filter(entry -> entry.getValue().contains(playerId))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /** Total number of pending invitations (used by /tournament debug). */
    public int pendingInvitationCount() {
        return this.pendingInvites.values().stream().mapToInt(Set::size).sum();
    }

    public boolean isLocked(UUID teamId) {
        Team team = this.teams.get(teamId);
        return team != null && team.locked();
    }

    public int maxPlayersPerTeam() {
        return this.config.tournament().playersPerTeam();
    }

    /** Creates a team with the given player as captain. */
    public synchronized Team createTeam(Player captain, String name) {
        if (teamOfPlayer(captain.uuid()).isPresent()) {
            throw new TeamException("You are already in a team.");
        }
        validateTeamName(name);
        Team team = new Team(
                UUID.randomUUID(),
                name,
                captain.uuid(),
                List.of(new TeamPlayer(captain.uuid(), captain.username(), TeamRole.CAPTAIN)),
                false);
        this.teams.put(team.id(), team);
        this.playerToTeam.put(captain.uuid(), team.id());
        this.eventBus.fire(new TeamCreatedEvent(team));
        return team;
    }

    /** Captain invites another player to their team. */
    public synchronized void invitePlayer(UUID captainId, UUID targetPlayerId) {
        Team team = requireTeamOf(captainId);
        requireCaptain(team, captainId);
        requireNotLocked(team);
        if (targetPlayerId.equals(captainId)) {
            throw new TeamException("You cannot invite yourself.");
        }
        if (teamOfPlayer(targetPlayerId).isPresent()) {
            throw new TeamException("That player is already in a team.");
        }
        if (team.players().size() >= maxPlayersPerTeam()) {
            throw new TeamException("Your team is full.");
        }
        Set<UUID> invites = this.pendingInvites.computeIfAbsent(team.id(), key -> new HashSet<>());
        if (!invites.add(targetPlayerId)) {
            throw new TeamException("This player already has a pending invitation from your team.");
        }
        this.eventBus.fire(new TeamInviteEvent(team, captainId, targetPlayerId));
    }

    /** A player accepts a pending invitation and joins the team. */
    public synchronized Team acceptInvite(Player player, UUID teamId) {
        Team team = requireTeam(teamId);
        requireNotLocked(team);
        if (teamOfPlayer(player.uuid()).isPresent()) {
            throw new TeamException("You are already in a team.");
        }
        Set<UUID> invites = this.pendingInvites.get(teamId);
        if (invites == null || !invites.contains(player.uuid())) {
            throw new TeamException("You have no pending invitation from this team.");
        }
        if (team.players().size() >= maxPlayersPerTeam()) {
            throw new TeamException("This team is full.");
        }
        invites.remove(player.uuid());
        List<TeamPlayer> updatedPlayers = new ArrayList<>(team.players());
        updatedPlayers.add(new TeamPlayer(player.uuid(), player.username(), TeamRole.MEMBER));
        Team updated = team.withPlayers(updatedPlayers);
        this.teams.put(team.id(), updated);
        this.playerToTeam.put(player.uuid(), team.id());
        this.eventBus.fire(new TeamJoinEvent(updated, player.uuid()));
        return updated;
    }

    /** A member leaves their team. The captain cannot leave: the team must be disbanded. */
    public synchronized void leaveTeam(Player player) {
        Team team = requireTeamOf(player.uuid());
        requireNotLocked(team);
        if (team.captainId().equals(player.uuid())) {
            throw new TeamException("The captain cannot leave the team, disband it instead.");
        }
        List<TeamPlayer> updatedPlayers = team.players().stream()
                .filter(member -> !member.playerId().equals(player.uuid()))
                .toList();
        this.teams.put(team.id(), team.withPlayers(updatedPlayers));
        this.playerToTeam.remove(player.uuid());
        this.eventBus.fire(new TeamLeaveEvent(team.withPlayers(updatedPlayers), player.uuid()));
    }

    /** The captain disbands their team; all members are freed. */
    public synchronized void disbandTeam(Player captain) {
        Team team = requireTeamOf(captain.uuid());
        requireCaptain(team, captain.uuid());
        requireNotLocked(team);
        this.teams.remove(team.id());
        this.pendingInvites.remove(team.id());
        team.players().forEach(member -> this.playerToTeam.remove(member.playerId()));
        this.eventBus.fire(new TeamDisbandedEvent(team));
    }

    /** Locks every team. Called when the tournament starts. */
    public synchronized void lockAllTeams() {
        this.teams.replaceAll((teamId, team) -> team.locked() ? team : team.withLocked(true));
    }

    /** Unlocks every team. Called when the tournament is cancelled. */
    public synchronized void unlockAllTeams() {
        this.teams.replaceAll((teamId, team) -> team.locked() ? team.withLocked(false) : team);
    }

    private void validateTeamName(String name) {
        if (name == null || name.isBlank()) {
            throw new TeamException("Team name must not be blank.");
        }
        if (name.length() > MAX_TEAM_NAME_LENGTH) {
            throw new TeamException("Team name is too long (max " + MAX_TEAM_NAME_LENGTH + " characters).");
        }
        boolean duplicate = this.teams.values().stream()
                .anyMatch(team -> team.name().equalsIgnoreCase(name));
        if (duplicate) {
            throw new TeamException("A team with this name already exists.");
        }
    }

    private Team requireTeam(UUID teamId) {
        Team team = this.teams.get(teamId);
        if (team == null) {
            throw new TeamException("This team does not exist.");
        }
        return team;
    }

    private Team requireTeamOf(UUID playerId) {
        UUID teamId = this.playerToTeam.get(playerId);
        if (teamId == null) {
            throw new TeamException("You are not in a team.");
        }
        return this.teams.get(teamId);
    }

    private static void requireCaptain(Team team, UUID playerId) {
        if (!team.captainId().equals(playerId)) {
            throw new TeamException("Only the captain can do this.");
        }
    }

    private static void requireNotLocked(Team team) {
        if (team.locked()) {
            throw new TeamException("This team is locked.");
        }
    }
}
