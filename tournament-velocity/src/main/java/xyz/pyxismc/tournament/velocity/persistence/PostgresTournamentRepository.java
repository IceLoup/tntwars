package xyz.pyxismc.tournament.velocity.persistence;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import xyz.pyxismc.tournament.common.dto.TournamentSnapshot;
import xyz.pyxismc.tournament.common.dto.TournamentSummary;
import xyz.pyxismc.tournament.common.enums.MatchStatus;
import xyz.pyxismc.tournament.common.enums.RoundState;
import xyz.pyxismc.tournament.common.enums.TeamRole;
import xyz.pyxismc.tournament.common.enums.TournamentState;
import xyz.pyxismc.tournament.common.model.Match;
import xyz.pyxismc.tournament.common.model.PlayerMatchStats;
import xyz.pyxismc.tournament.common.model.Round;
import xyz.pyxismc.tournament.common.model.Team;
import xyz.pyxismc.tournament.common.model.TeamMatchStats;
import xyz.pyxismc.tournament.common.model.TeamPlayer;
import xyz.pyxismc.tournament.common.model.Tournament;
import xyz.pyxismc.tournament.common.result.MatchResult;
import xyz.pyxismc.tournament.common.result.Placement;
import xyz.pyxismc.tournament.common.result.TeamResult;

/**
 * PostgreSQL-backed {@link TournamentRepository} using a HikariCP pool.
 * The schema is created at startup from {@code db/schema.sql}.
 */
public final class PostgresTournamentRepository implements TournamentRepository {

    private static final String SCHEMA_RESOURCE = "/db/schema.sql";

    /** A team player row joined with its team id. */
    private record TeamPlayerRow(UUID teamId, TeamPlayer player) {
    }

    private final DataSource dataSource;

    public PostgresTournamentRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        initSchema();
    }

    private void initSchema() {
        try (Connection connection = this.dataSource.getConnection();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        PostgresTournamentRepository.class.getResourceAsStream(SCHEMA_RESOURCE),
                        StandardCharsets.UTF_8));
                Statement statement = connection.createStatement()) {
            StringBuilder schema = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                schema.append(line).append('\n');
            }
            statement.execute(schema.toString());
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Cannot initialise the tournament schema", e);
        }
    }

    @Override
    public void saveTournament(TournamentSnapshot snapshot) {
        Tournament tournament = snapshot.tournament();
        try (Connection connection = this.dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                insertTournament(connection, tournament, snapshot.championTeamId());
                for (Team team : snapshot.teams()) {
                    insertTeam(connection, tournament.id(), team);
                }
                for (Round round : snapshot.rounds()) {
                    insertRound(connection, tournament.id(), round);
                }
                for (Match match : snapshot.matches()) {
                    insertMatch(connection, tournament.id(), match, snapshot.results().get(match.id()));
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save tournament " + tournament.id(), e);
        }
    }

    @Override
    public List<TournamentSummary> listTournaments(int limit) {
        String sql = """
                SELECT t.id, t.name, t.state, t.created_at, t.started_at, t.finished_at,
                       t.champion_team_id, COUNT(tm.id) AS team_count
                FROM tournaments t
                LEFT JOIN teams tm ON tm.tournament_id = t.id
                GROUP BY t.id
                ORDER BY t.created_at DESC
                LIMIT ?""";
        try (Connection connection = this.dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet rs = statement.executeQuery()) {
                List<TournamentSummary> summaries = new ArrayList<>();
                while (rs.next()) {
                    summaries.add(new TournamentSummary(
                            rs.getObject("id", UUID.class),
                            rs.getString("name"),
                            TournamentState.valueOf(rs.getString("state")),
                            toInstant(rs.getTimestamp("created_at")),
                            toInstant(rs.getTimestamp("started_at")),
                            toInstant(rs.getTimestamp("finished_at")),
                            rs.getInt("team_count"),
                            rs.getObject("champion_team_id", UUID.class)));
                }
                return List.copyOf(summaries);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot list tournaments", e);
        }
    }

    @Override
    public Optional<TournamentSnapshot> getTournament(UUID tournamentId) {
        try (Connection connection = this.dataSource.getConnection()) {
            Tournament tournament = selectTournament(connection, tournamentId).orElse(null);
            if (tournament == null) {
                return Optional.empty();
            }
            List<Team> teams = selectTeams(connection, tournamentId);
            List<Round> rounds = selectRounds(connection, tournamentId);
            List<Match> matches = selectMatches(connection, tournamentId);
            Map<UUID, MatchResult> results = new HashMap<>();
            for (Match match : matches) {
                selectResult(connection, match, results);
            }
            UUID championTeamId = selectChampion(connection, tournamentId);
            return Optional.of(new TournamentSnapshot(
                    tournament, teams, rounds, matches, results, championTeamId));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot load tournament " + tournamentId, e);
        }
    }

    // ------------------------------------------------------------------
    // Inserts
    // ------------------------------------------------------------------

    private static void insertTournament(Connection connection, Tournament tournament, UUID championTeamId)
            throws SQLException {
        String sql = """
                INSERT INTO tournaments (id, name, state, created_at, started_at, finished_at, champion_team_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tournament.id());
            statement.setString(2, tournament.name());
            statement.setString(3, tournament.state().name());
            statement.setTimestamp(4, Timestamp.from(tournament.createdAt()));
            statement.setTimestamp(5, Timestamp.from(tournament.startedAt()));
            statement.setTimestamp(6, Timestamp.from(tournament.finishedAt()));
            statement.setObject(7, championTeamId);
            statement.executeUpdate();
        }
    }

    private static void insertTeam(Connection connection, UUID tournamentId, Team team) throws SQLException {
        String sql = "INSERT INTO teams (id, tournament_id, name, captain_id) VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, team.id());
            statement.setObject(2, tournamentId);
            statement.setString(3, team.name());
            statement.setObject(4, team.captainId());
            statement.executeUpdate();
        }
        String playersSql = """
                INSERT INTO team_players (tournament_id, team_id, player_id, username, role)
                VALUES (?, ?, ?, ?, ?)""";
        try (PreparedStatement statement = connection.prepareStatement(playersSql)) {
            for (TeamPlayer player : team.players()) {
                statement.setObject(1, tournamentId);
                statement.setObject(2, team.id());
                statement.setObject(3, player.playerId());
                statement.setString(4, player.username());
                statement.setString(5, player.role().name());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertRound(Connection connection, UUID tournamentId, Round round) throws SQLException {
        String sql = "INSERT INTO rounds (id, tournament_id, number, state) VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, round.id());
            statement.setObject(2, tournamentId);
            statement.setInt(3, round.number());
            statement.setString(4, round.state().name());
            statement.executeUpdate();
        }
    }

    private static void insertMatch(Connection connection, UUID tournamentId, Match match, MatchResult result)
            throws SQLException {
        String sql = """
                INSERT INTO matches (id, tournament_id, round_id, server_id, status, duration_ms, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, match.id());
            statement.setObject(2, tournamentId);
            statement.setObject(3, match.roundId());
            statement.setString(4, match.serverId());
            statement.setString(5, match.status().name());
            statement.setLong(6, result == null ? 0 : result.duration().toMillis());
            statement.setTimestamp(7, Timestamp.from(result == null ? Instant.now() : result.finishedAt()));
            statement.executeUpdate();
        }
        if (result != null) {
            insertResult(connection, match.id(), result);
        }
    }

    private static void insertResult(Connection connection, UUID matchId, MatchResult result) throws SQLException {
        String teamSql = """
                INSERT INTO match_team_results (match_id, team_id, placement, placement_rank, kills, deaths)
                VALUES (?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement statement = connection.prepareStatement(teamSql)) {
            for (TeamResult teamResult : result.results()) {
                TeamMatchStats stats = result.teamStats().getOrDefault(teamResult.teamId(),
                        new TeamMatchStats(teamResult.teamId(), 0, 0));
                statement.setObject(1, matchId);
                statement.setObject(2, teamResult.teamId());
                statement.setString(3, teamResult.placement().name());
                statement.setInt(4, teamResult.placement().rank());
                statement.setLong(5, stats.kills());
                statement.setLong(6, stats.deaths());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        String playerSql = """
                INSERT INTO match_player_stats (match_id, player_id, team_id, kills, deaths, assists, damage)
                VALUES (?, ?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement statement = connection.prepareStatement(playerSql)) {
            for (PlayerMatchStats stats : result.playerStats().values()) {
                statement.setObject(1, matchId);
                statement.setObject(2, stats.playerId());
                statement.setObject(3, stats.teamId());
                statement.setLong(4, stats.kills());
                statement.setLong(5, stats.deaths());
                statement.setLong(6, stats.assists());
                statement.setLong(7, stats.damage());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    private static Optional<Tournament> selectTournament(Connection connection, UUID tournamentId)
            throws SQLException {
        String sql = """
                SELECT id, name, state, created_at, started_at, finished_at
                FROM tournaments WHERE id = ?""";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tournamentId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Tournament(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        TournamentState.valueOf(rs.getString("state")),
                        List.of(),
                        List.of(),
                        toInstant(rs.getTimestamp("created_at")),
                        toInstant(rs.getTimestamp("started_at")),
                        toInstant(rs.getTimestamp("finished_at"))));
            }
        }
    }

    private static List<Team> selectTeams(Connection connection, UUID tournamentId) throws SQLException {
        String sql = "SELECT id, name, captain_id FROM teams WHERE tournament_id = ? ORDER BY name";
        Map<UUID, Team> teams = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tournamentId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    UUID teamId = rs.getObject("id", UUID.class);
                    teams.put(teamId, new Team(
                            teamId,
                            rs.getString("name"),
                            rs.getObject("captain_id", UUID.class),
                            List.of(),
                            false));
                }
            }
        }
        Map<UUID, List<TeamPlayer>> playersByTeam = new LinkedHashMap<>();
        for (TeamPlayerRow row : selectPlayers(connection, tournamentId)) {
            playersByTeam.computeIfAbsent(row.teamId(), key -> new ArrayList<>()).add(row.player());
        }
        List<Team> result = new ArrayList<>();
        for (Team team : teams.values()) {
            List<TeamPlayer> players = playersByTeam.getOrDefault(team.id(), List.of());
            result.add(team.withPlayers(players));
        }
        return List.copyOf(result);
    }

    private static List<TeamPlayerRow> selectPlayers(Connection connection, UUID tournamentId) throws SQLException {
        String sql = """
                SELECT team_id, player_id, username, role FROM team_players
                WHERE tournament_id = ?""";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tournamentId);
            try (ResultSet rs = statement.executeQuery()) {
                List<TeamPlayerRow> players = new ArrayList<>();
                while (rs.next()) {
                    players.add(new TeamPlayerRow(
                            rs.getObject("team_id", UUID.class),
                            new TeamPlayer(
                                    rs.getObject("player_id", UUID.class),
                                    rs.getString("username"),
                                    TeamRole.valueOf(rs.getString("role")))));
                }
                return List.copyOf(players);
            }
        }
    }

    private static List<Round> selectRounds(Connection connection, UUID tournamentId) throws SQLException {
        String sql = "SELECT id, number, state FROM rounds WHERE tournament_id = ? ORDER BY number";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tournamentId);
            try (ResultSet rs = statement.executeQuery()) {
                List<Round> rounds = new ArrayList<>();
                while (rs.next()) {
                    rounds.add(new Round(
                            rs.getObject("id", UUID.class),
                            tournamentId,
                            rs.getInt("number"),
                            RoundState.valueOf(rs.getString("state")),
                            List.of()));
                }
                return List.copyOf(rounds);
            }
        }
    }

    private static List<Match> selectMatches(Connection connection, UUID tournamentId) throws SQLException {
        String sql = """
                SELECT id, round_id, server_id, status, duration_ms, finished_at
                FROM matches WHERE tournament_id = ?""";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tournamentId);
            try (ResultSet rs = statement.executeQuery()) {
                List<Match> matches = new ArrayList<>();
                while (rs.next()) {
                    matches.add(new Match(
                            rs.getObject("id", UUID.class),
                            rs.getObject("round_id", UUID.class),
                            List.of(),
                            rs.getString("server_id"),
                            MatchStatus.valueOf(rs.getString("status"))));
                }
                return List.copyOf(matches);
            }
        }
    }

    private static void selectResult(Connection connection, Match match, Map<UUID, MatchResult> results)
            throws SQLException {
        String resultSql = """
                SELECT duration_ms, finished_at FROM matches WHERE id = ?""";
        long durationMs;
        Instant finishedAt;
        try (PreparedStatement statement = connection.prepareStatement(resultSql)) {
            statement.setObject(1, match.id());
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                durationMs = rs.getLong("duration_ms");
                finishedAt = toInstant(rs.getTimestamp("finished_at"));
            }
        }
        String teamSql = """
                SELECT team_id, placement, kills, deaths
                FROM match_team_results WHERE match_id = ?""";
        List<TeamResult> teamResults = new ArrayList<>();
        Map<UUID, TeamMatchStats> teamStats = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(teamSql)) {
            statement.setObject(1, match.id());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    UUID teamId = rs.getObject("team_id", UUID.class);
                    teamResults.add(new TeamResult(teamId,
                            Placement.valueOf(rs.getString("placement"))));
                    teamStats.put(teamId, new TeamMatchStats(teamId,
                            rs.getLong("kills"), rs.getLong("deaths")));
                }
            }
        }
        if (teamResults.isEmpty()) {
            return;
        }
        String playerSql = """
                SELECT player_id, team_id, kills, deaths, assists, damage
                FROM match_player_stats WHERE match_id = ?""";
        Map<UUID, PlayerMatchStats> playerStats = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(playerSql)) {
            statement.setObject(1, match.id());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    UUID playerId = rs.getObject("player_id", UUID.class);
                    playerStats.put(playerId, new PlayerMatchStats(
                            playerId,
                            rs.getObject("team_id", UUID.class),
                            rs.getLong("kills"),
                            rs.getLong("deaths"),
                            rs.getLong("assists"),
                            rs.getLong("damage")));
                }
            }
        }
        results.put(match.id(), new MatchResult(
                match.id(),
                teamResults,
                teamStats,
                playerStats,
                Duration.ofMillis(durationMs),
                finishedAt));
    }

    private static UUID selectChampion(Connection connection, UUID tournamentId) throws SQLException {
        String sql = "SELECT champion_team_id FROM tournaments WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tournamentId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getObject("champion_team_id", UUID.class) : null;
            }
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}