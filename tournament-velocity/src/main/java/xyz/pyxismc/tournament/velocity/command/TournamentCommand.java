package xyz.pyxismc.tournament.velocity.command;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;

import xyz.pyxismc.tournament.common.dto.TournamentSnapshot;
import xyz.pyxismc.tournament.common.dto.TournamentSummary;
import xyz.pyxismc.tournament.common.enums.MatchStatus;
import xyz.pyxismc.tournament.common.enums.TournamentState;
import xyz.pyxismc.tournament.common.model.Match;
import xyz.pyxismc.tournament.common.model.Round;
import xyz.pyxismc.tournament.common.model.Team;
import xyz.pyxismc.tournament.common.model.Tournament;
import xyz.pyxismc.tournament.velocity.config.TournamentConfig;
import xyz.pyxismc.tournament.velocity.persistence.TournamentRepository;
import xyz.pyxismc.tournament.velocity.round.RoundManager;
import xyz.pyxismc.tournament.velocity.team.TeamManager;
import xyz.pyxismc.tournament.velocity.tournament.TournamentException;
import xyz.pyxismc.tournament.velocity.tournament.TournamentManager;

/**
 * {@code /tournament} command: start, stop, pause, resume, info and debug.
 * Admin subcommands require their {@code tournament.*} permission; the
 * {@code tournament.admin} permission grants everything.
 */
public final class TournamentCommand implements SimpleCommand {

    private final TournamentConfig config;
    private final TournamentManager tournamentManager;
    private final RoundManager roundManager;
    private final TeamManager teamManager;
    private final TournamentRepository repository;

    public TournamentCommand(
            TournamentConfig config,
            TournamentManager tournamentManager,
            RoundManager roundManager,
            TeamManager teamManager,
            TournamentRepository repository
    ) {
        this.config = config;
        this.tournamentManager = tournamentManager;
        this.roundManager = roundManager;
        this.teamManager = teamManager;
        this.repository = repository;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            CommandMessages.info(source, "/tournament <start|stop|pause|resume|info|debug|history>");
            return;
        }

        switch (args[0].toLowerCase()) {
            case "start" -> start(source);
            case "stop" -> stop(source);
            case "pause" -> pause(source);
            case "resume" -> resume(source);
            case "info" -> info(source);
            case "debug" -> debug(source);
            case "history" -> history(source, args);
            default -> CommandMessages.info(source, "/tournament <start|stop|pause|resume|info|debug|history>");
        }
    }

    private void start(CommandSource source) {
        if (!hasPermission(source, "tournament.start")) {
            deny(source);
            return;
        }
        try {
            Tournament tournament = this.tournamentManager.startTournament();
            this.tournamentManager.startNextRound();
            Round round = this.roundManager.getCurrentRound().orElseThrow();
            CommandMessages.success(source, "Tournament '" + tournament.name() + "' started ("
                    + this.teamManager.getTeams().size() + " teams, round " + round.number()
                    + " with " + this.roundManager.getMatchesOfRound(round.id()).size() + " match(es)).");
        } catch (TournamentException e) {
            CommandMessages.error(source, e.getMessage());
        }
    }

    private void stop(CommandSource source) {
        if (!hasPermission(source, "tournament.stop")) {
            deny(source);
            return;
        }
        try {
            this.tournamentManager.stopTournament();
            CommandMessages.success(source, "Tournament cancelled. Teams are unlocked again.");
        } catch (TournamentException e) {
            CommandMessages.error(source, e.getMessage());
        }
    }

    private void pause(CommandSource source) {
        if (!hasPermission(source, "tournament.pause")) {
            deny(source);
            return;
        }
        try {
            this.tournamentManager.pauseTournament();
            CommandMessages.success(source, "Tournament paused.");
        } catch (TournamentException e) {
            CommandMessages.error(source, e.getMessage());
        }
    }

    private void resume(CommandSource source) {
        if (!hasPermission(source, "tournament.resume")) {
            deny(source);
            return;
        }
        try {
            this.tournamentManager.resumeTournament();
            CommandMessages.success(source, "Tournament resumed.");
        } catch (TournamentException e) {
            CommandMessages.error(source, e.getMessage());
        }
    }

    private void info(CommandSource source) {
        if (!hasPermission(source, "tournament.info")) {
            deny(source);
            return;
        }
        Optional<Tournament> tournament = this.tournamentManager.getActiveTournament();
        if (tournament.isEmpty()) {
            CommandMessages.info(source, "No tournament is active.");
            return;
        }
        Tournament current = tournament.get();
        CommandMessages.info(source, "Tournament '" + current.name() + "'");
        CommandMessages.debug(source, "State: " + current.state());
        if (current.startedAt() != null) {
            CommandMessages.debug(source, "Started: " + current.startedAt() + " ("
                    + formatAge(Duration.between(current.startedAt(), Instant.now())) + " ago)");
        }
        CommandMessages.debug(source, "Teams registered: " + this.teamManager.getTeams().size());
        CommandMessages.debug(source, "Teams in tournament: " + current.teamIds().size());
        this.roundManager.getCurrentRound().ifPresent(round -> {
            List<Match> matches = this.roundManager.getMatchesOfRound(round.id());
            CommandMessages.debug(source, "Round " + round.number() + " (" + round.state()
                    + "): " + matches.size() + " match(es)");
            for (Match match : matches) {
                CommandMessages.debug(source, "  - " + match.status() + " "
                        + match.teamIds().size() + " teams, server: "
                        + (match.serverId() == null ? "none" : match.serverId()));
            }
        });
        this.tournamentManager.getChampionTeamId().ifPresent(champion ->
                CommandMessages.success(source, "Champion: " + champion));
    }

    private void debug(CommandSource source) {
        if (!hasPermission(source, "tournament.debug")) {
            deny(source);
            return;
        }
        TournamentConfig.DatabaseSettings database = this.config.database();
        TournamentConfig.ServerSettings server = this.config.server();
        TournamentState state = this.tournamentManager.getState().orElse(null);
        List<Team> teams = this.teamManager.getTeams();
        long locked = teams.stream().filter(Team::locked).count();
        List<Round> rounds = this.roundManager.getRounds();
        long runningMatches = this.roundManager.getMatches().stream()
                .filter(match -> match.status() == MatchStatus.RUNNING)
                .count();

        CommandMessages.debug(source, "== Tournament debug ==");
        CommandMessages.debug(source, "Active state: " + state);
        CommandMessages.debug(source, "Tournaments this session: " + this.tournamentManager.getTournaments().size());
        CommandMessages.debug(source, "Rounds: " + rounds.size() + ", running matches: " + runningMatches);
        CommandMessages.debug(source, "Teams: " + teams.size() + " (locked: " + locked + ")");
        CommandMessages.debug(source, "Pending invitations: " + this.teamManager.pendingInvitationCount());
        CommandMessages.debug(source, "Players per team: " + this.config.tournament().playersPerTeam());
        CommandMessages.debug(source, "Max teams per match: " + this.config.tournament().maxTeamsPerMatch());
        CommandMessages.debug(source, "Lobby: " + this.config.lobby().server());
        CommandMessages.debug(source, "Match template: " + server.template());
        CommandMessages.debug(source, "Database: " + database.host() + ":" + database.port() + "/" + database.database());
    }

    private void history(CommandSource source, String[] args) {
        if (!hasPermission(source, "tournament.history")) {
            deny(source);
            return;
        }
        if (this.repository == null) {
            CommandMessages.error(source, "History is unavailable (no database connection).");
            return;
        }
        try {
            if (args.length >= 2) {
                showTournamentDetails(source, UUID.fromString(args[1]));
            } else {
                List<TournamentSummary> summaries = this.repository.listTournaments(10);
                if (summaries.isEmpty()) {
                    CommandMessages.info(source, "No tournaments stored yet.");
                    return;
                }
                for (TournamentSummary summary : summaries) {
                    CommandMessages.info(source, "#" + summary.id().toString().substring(0, 8)
                            + " '" + summary.name() + "' (" + summary.state() + ", "
                            + summary.teamCount() + " teams)");
                }
                CommandMessages.debug(source, "Use /tournament history <id> for details.");
            }
        } catch (IllegalArgumentException e) {
            CommandMessages.error(source, "Invalid tournament id.");
        } catch (RuntimeException e) {
            CommandMessages.error(source, "History lookup failed: " + e.getMessage());
        }
    }

    private void showTournamentDetails(CommandSource source, UUID tournamentId) {
        Optional<TournamentSnapshot> snapshot = this.repository.getTournament(tournamentId);
        if (snapshot.isEmpty()) {
            CommandMessages.error(source, "No tournament with this id was found.");
            return;
        }
        TournamentSnapshot stored = snapshot.get();
        Tournament tournament = stored.tournament();
        CommandMessages.info(source, "Tournament '" + tournament.name() + "' (" + tournament.state() + ")");
        CommandMessages.debug(source, "Created: " + tournament.createdAt()
                + ", finished: " + tournament.finishedAt());
        CommandMessages.debug(source, "Teams: " + stored.teams().size()
                + ", rounds: " + stored.rounds().size()
                + ", matches: " + stored.matches().size());
        if (stored.championTeamId() != null) {
            String champion = stored.teamOf(stored.championTeamId()).map(Team::name)
                    .orElse(stored.championTeamId().toString());
            CommandMessages.success(source, "Champion: " + champion);
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            return List.of("start", "stop", "pause", "resume", "info", "debug", "history");
        }
        return List.of();
    }

    private static boolean hasPermission(CommandSource source, String permission) {
        return source.hasPermission(permission) || source.hasPermission("tournament.admin");
    }

    private static void deny(CommandSource source) {
        CommandMessages.error(source, "You do not have permission to do this.");
    }

    private static String formatAge(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        return seconds / 60 + "m" + seconds % 60 + "s";
    }
}
