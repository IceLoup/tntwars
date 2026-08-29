package xyz.pyxismc.tournament.velocity.tournament;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import xyz.pyxismc.tournament.common.enums.TournamentState;
import xyz.pyxismc.tournament.common.model.Round;
import xyz.pyxismc.tournament.common.model.Team;
import xyz.pyxismc.tournament.common.model.Tournament;
import xyz.pyxismc.tournament.common.result.MatchResult;
import xyz.pyxismc.tournament.velocity.config.TournamentConfig;
import xyz.pyxismc.tournament.velocity.event.TournamentCancelledEvent;
import xyz.pyxismc.tournament.velocity.event.TournamentEventBus;
import xyz.pyxismc.tournament.velocity.event.TournamentFinishedEvent;
import xyz.pyxismc.tournament.velocity.event.TournamentStartedEvent;
import xyz.pyxismc.tournament.velocity.round.RoundException;
import xyz.pyxismc.tournament.velocity.round.RoundManager;
import xyz.pyxismc.tournament.velocity.team.TeamManager;

/**
 * Manages the tournament lifecycle. This is the only class allowed to change
 * a tournament's state, always through {@link TournamentStateMachine}.
 * <p>
 * Rounds are built and advanced by the {@link RoundManager}; this class
 * drives the tournament state around them (STARTING, ROUND_RUNNING,
 * ROUND_FINISHED, FINISHED).
 */
public final class TournamentManager {

    private final TournamentConfig config;
    private final TeamManager teamManager;
    private final RoundManager roundManager;
    private final TournamentEventBus eventBus;
    private final TournamentStateMachine stateMachine = new TournamentStateMachine();

    private final Map<UUID, Tournament> tournaments = new ConcurrentHashMap<>();
    private volatile UUID activeTournamentId;
    private volatile TournamentState pausedFrom;

    public TournamentManager(
            TournamentConfig config,
            TeamManager teamManager,
            RoundManager roundManager,
            TournamentEventBus eventBus
    ) {
        this.config = config;
        this.teamManager = teamManager;
        this.roundManager = roundManager;
        this.eventBus = eventBus;
    }

    public Optional<Tournament> getActiveTournament() {
        UUID id = this.activeTournamentId;
        return id == null ? Optional.empty() : Optional.ofNullable(this.tournaments.get(id));
    }

    public Optional<TournamentState> getState() {
        return getActiveTournament().map(Tournament::state);
    }

    /** Immutable snapshot of all tournaments ever created in this session. */
    public List<Tournament> getTournaments() {
        return List.copyOf(this.tournaments.values());
    }

    /**
     * Creates a tournament in REGISTRATION state. Teams are registered
     * afterwards; the team list is captured when the tournament starts.
     */
    public synchronized Tournament createTournament(String name) {
        Optional<Tournament> active = getActiveTournament();
        if (active.isPresent() && !this.stateMachine.isTerminal(active.get().state())) {
            throw new TournamentException("A tournament is already active.");
        }
        if (name == null || name.isBlank()) {
            throw new TournamentException("Tournament name must not be blank.");
        }
        Tournament tournament = new Tournament(
                UUID.randomUUID(),
                name,
                TournamentState.REGISTRATION,
                List.of(),
                List.of(),
                Instant.now(),
                null,
                null);
        this.tournaments.put(tournament.id(), tournament);
        this.activeTournamentId = tournament.id();
        return tournament;
    }

    /**
     * Starts the tournament: validates that every team is complete (exactly
     * {@code players-per-team} players) and that at least 3 teams are
     * registered, locks all teams, builds round 1, then moves
     * REGISTRATION -> STARTING.
     */
    public synchronized Tournament startTournament() {
        Tournament tournament = requireActive();
        requireState(tournament, TournamentState.REGISTRATION);

        List<Team> teams = this.teamManager.getTeams();
        if (teams.size() < 3) {
            throw new TournamentException("Cannot start the tournament: at least 3 teams are required.");
        }
        int expectedPlayers = this.teamManager.maxPlayersPerTeam();
        long incomplete = teams.stream()
                .filter(team -> team.players().size() != expectedPlayers)
                .count();
        if (incomplete > 0) {
            throw new TournamentException("Cannot start the tournament: " + incomplete
                    + " team(s) are incomplete (exactly " + expectedPlayers + " players required).");
        }

        TournamentState next = this.transition(tournament, TournamentState.STARTING);
        Tournament started = tournament
                .withState(next)
                .withStartedAt(Instant.now())
                .withTeamIds(teams.stream().map(Team::id).toList());
        this.tournaments.put(started.id(), started);

        Round firstRound = this.roundManager.createFirstRound(started.id(), teams);
        started = started.withRoundIds(Stream.concat(started.roundIds().stream(), Stream.of(firstRound.id())).toList());
        this.tournaments.put(started.id(), started);

        this.teamManager.lockAllTeams();
        this.eventBus.fire(new TournamentStartedEvent(started));
        return started;
    }

    /**
     * Force-starts the tournament: reuses the active tournament when it is in
     * REGISTRATION, otherwise creates a new one with the given name. Team and
     * completeness validations are skipped (only a minimum of 2 teams is
     * enforced, because a round cannot be built with fewer). Teams are locked,
     * round 1 is built, and the tournament moves REGISTRATION -> STARTING.
     */
    public synchronized Tournament forceStartTournament(String name) {
        Optional<Tournament> active = getActiveTournament();
        Tournament tournament;
        if (active.isPresent() && !this.stateMachine.isTerminal(active.get().state())) {
            tournament = active.get();
            if (tournament.state() != TournamentState.REGISTRATION) {
                throw new TournamentException("Tournament is in state " + tournament.state()
                        + " and cannot be force-started.");
            }
        } else {
            tournament = createTournament(name);
        }

        List<Team> teams = this.teamManager.getTeams();
        if (teams.size() < 2) {
            throw new TournamentException("Cannot start the tournament: at least 2 teams are required.");
        }

        TournamentState next = this.transition(tournament, TournamentState.STARTING);
        Tournament started = tournament
                .withState(next)
                .withStartedAt(Instant.now())
                .withTeamIds(teams.stream().map(Team::id).toList());
        this.tournaments.put(started.id(), started);

        Round firstRound = this.roundManager.createFirstRound(started.id(), teams);
        started = started.withRoundIds(Stream.concat(started.roundIds().stream(), Stream.of(firstRound.id())).toList());
        this.tournaments.put(started.id(), started);

        this.teamManager.lockAllTeams();
        this.eventBus.fire(new TournamentStartedEvent(started));
        return started;
    }

    /**
     * Starts the current round (STARTING -> ROUND_RUNNING, or
     * ROUND_FINISHED -> ROUND_RUNNING after a round completed).
     */
    public synchronized Tournament startNextRound() {
        Tournament tournament = requireActive();
        TournamentState state = tournament.state();
        if (state != TournamentState.STARTING && state != TournamentState.ROUND_FINISHED) {
            throw new TournamentException("Cannot start a round from state " + state + ".");
        }
        Round current = this.roundManager.getCurrentRound()
                .orElseThrow(() -> new TournamentException("No round to start."));
        TournamentState next = this.transition(tournament, TournamentState.ROUND_RUNNING);
        Tournament running = tournament.withState(next);
        this.tournaments.put(running.id(), running);
        this.roundManager.startRound(current.id());
        return running;
    }

    /**
     * Records a match result reported by a Paper server. When the round
     * completes, the next round is built and started automatically; when
     * the strategy declares the tournament over, it finishes
     * (ROUND_RUNNING -> ROUND_FINISHED -> FINISHED).
     */
    public synchronized Tournament submitMatchResult(UUID matchId, MatchResult result) {
        Tournament tournament = requireActive();
        requireState(tournament, TournamentState.ROUND_RUNNING);
        Round before = this.roundManager.getCurrentRound().orElseThrow();
        try {
            this.roundManager.submitMatchResult(matchId, result);
        } catch (RoundException e) {
            throw new TournamentException(e.getMessage());
        }
        Optional<Round> after = this.roundManager.getCurrentRound();
        if (after.isEmpty()) {
            Tournament finishedRound = tournament.withState(
                    this.transition(tournament, TournamentState.ROUND_FINISHED));
            this.tournaments.put(finishedRound.id(), finishedRound);
            return finishTournament();
        }
        if (!after.get().id().equals(before.id())) {
            Tournament finishedRound = tournament.withState(
                    this.transition(tournament, TournamentState.ROUND_FINISHED));
            this.tournaments.put(finishedRound.id(), finishedRound);
            Tournament nextRound = finishedRound.withState(
                    this.transition(finishedRound, TournamentState.ROUND_RUNNING));
            this.tournaments.put(nextRound.id(), nextRound);
            return nextRound;
        }
        return tournament;
    }

    /** Pauses the tournament. Allowed while starting or during rounds. */
    public synchronized Tournament pauseTournament() {
        Tournament tournament = requireActive();
        TournamentState next = this.transition(tournament, TournamentState.PAUSED);
        this.pausedFrom = tournament.state();
        Tournament paused = tournament.withState(next);
        this.tournaments.put(paused.id(), paused);
        return paused;
    }

    /** Resumes the tournament to the state it was paused from. */
    public synchronized Tournament resumeTournament() {
        Tournament tournament = requireActive();
        TournamentState target = this.pausedFrom == null ? TournamentState.ROUND_RUNNING : this.pausedFrom;
        TournamentState next = this.transition(tournament, target);
        this.pausedFrom = null;
        Tournament resumed = tournament.withState(next);
        this.tournaments.put(resumed.id(), resumed);
        return resumed;
    }

    /** Cancels the tournament. Teams are unlocked again. */
    public synchronized Tournament stopTournament() {
        Tournament tournament = requireActive();
        TournamentState next = this.transition(tournament, TournamentState.CANCELLED);
        Tournament cancelled = tournament.withState(next).withFinishedAt(Instant.now());
        this.tournaments.put(cancelled.id(), cancelled);
        this.roundManager.cancelAll();
        this.teamManager.unlockAllTeams();
        this.eventBus.fire(new TournamentCancelledEvent(cancelled));
        return cancelled;
    }

    /** Champion team of a finished tournament, if known. */
    public Optional<UUID> getChampionTeamId() {
        return this.roundManager.getChampionTeamId();
    }

    /**
     * Finishes the tournament (only possible from ROUND_FINISHED; called by
     * the round progression when there is no next round).
     */
    public synchronized Tournament finishTournament() {
        Tournament tournament = requireActive();
        TournamentState next = this.transition(tournament, TournamentState.FINISHED);
        Tournament finished = tournament.withState(next).withFinishedAt(Instant.now());
        this.tournaments.put(finished.id(), finished);
        this.eventBus.fire(new TournamentFinishedEvent(finished));
        return finished;
    }

    private Tournament requireActive() {
        return getActiveTournament().orElseThrow(() -> new TournamentException("No tournament is active."));
    }

    /** Applies a state change through the centralized state machine. */
    private TournamentState transition(Tournament tournament, TournamentState target) {
        try {
            return this.stateMachine.requireTransition(tournament.state(), target);
        } catch (IllegalStateException e) {
            throw new TournamentException(e.getMessage());
        }
    }

    private static void requireState(Tournament tournament, TournamentState expected) {
        if (tournament.state() != expected) {
            throw new TournamentException("Tournament is in state " + tournament.state()
                    + ", expected " + expected + ".");
        }
    }
}
