package xyz.pyxismc.tournament.paper.match;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.bukkit.scheduler.BukkitTask;
import xyz.pyxismc.tournament.paper.message.MiniMessageUtil;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles the TNTWars sidebar scoreboard.
 *
 * The scoreboard is created once per player.
 * Visible content is updated through Team prefixes, preventing flickering.
 *
 * Lobby scoreboard:
 *
 *  01  Empty
 *  02  Division » Diamond
 *  03  Empty
 *  04  - <head:player> player
 *  05  - <head:player2> player2
 *  06  - <head:player3> player3
 *  07  Empty
 *  08  🌍 Europe (45ms)
 *  09  Empty
 *
 * If the player has no team:
 *
 *  04  - No Team
 *
 * Lines 05 and 06 remain empty.
 */
public final class ScoreboardHandler {

    // ========================================================================
    // CONFIGURATION
    // ========================================================================

    private static final String OBJECTIVE_NAME = "tntwars";

    private static final String MATCH_TEAM_PREFIX = "tw_team_";

    private static final String TITLE =
            "<gradient:#8693AB:#BDD4E7>ᴛɴᴛᴡᴀʀꜱ</gradient>";

    private static final int LINE_COUNT = 11;

    /*
     * Invisible unique entries used internally by the scoreboard.
     *
     * Do NOT use private Unicode characters such as \uE001.
     * Those were responsible for the white squares visible in-game.
     */
    private static final String[] LINE_ENTRIES = {
            ChatColor.BLACK.toString(),
            ChatColor.DARK_BLUE.toString(),
            ChatColor.DARK_GREEN.toString(),
            ChatColor.DARK_AQUA.toString(),
            ChatColor.DARK_RED.toString(),
            ChatColor.DARK_PURPLE.toString(),
            ChatColor.GOLD.toString(),
            ChatColor.GRAY.toString(),
            ChatColor.DARK_GRAY.toString(),
            ChatColor.BLUE.toString(),
            ChatColor.GREEN.toString()
    };

    // ========================================================================
    // COLORS
    // ========================================================================

    private static final String COLOR_PRIMARY = "#BDD4E7";
    private static final String COLOR_SECONDARY = "#8693AB";
    private static final String COLOR_ERROR = "<red>";
    private static final String COLOR_GREEN = "#9cffb8";

    // ========================================================================
    // FIELDS
    // ========================================================================

    private final JavaPlugin plugin;

    private final Map<UUID, PlayerBoard> playerBoards =
            new HashMap<>();

    /**
     * Lobby team of each player.
     *
     * The scoreboard does not create or own the team.
     * Your lobby/team command system can update this map through
     * setLobbyTeam().
     */
    private final Map<UUID, List<UUID>> lobbyTeams =
            new HashMap<>();

    private volatile MatchSession session;

    private volatile Map<UUID, RuntimeTeam> runtimeTeams =
            Map.of();

    private volatile List<MatchTeamDef> matchTeams =
            List.of();

    private MatchSession finishedSession;

    private String winner;

    private BukkitTask scoreboardTask;

    // ========================================================================
    // MATCH TEAM DEFINITION
    // ========================================================================

    /**
     * Configuration of a match team used for name coloring.
     */
    public record MatchTeamDef(
            String displayName,
            ChatColor color,
            List<String> memberNames
    ) {
    }

    // ========================================================================
    // CONSTRUCTOR
    // ========================================================================

    public ScoreboardHandler(JavaPlugin plugin) {
        this.plugin = plugin;
        startScoreboardTask();
    }

    // ========================================================================
    // SCOREBOARD TASK
    // ========================================================================

    private void startScoreboardTask() {

        scoreboardTask =
                Bukkit.getScheduler().runTaskTimer(
                        plugin,
                        this::updateScoreboards,
                        0L,
                        20L
                );
    }

    public void stopScoreboardTask() {

        if (
                scoreboardTask != null
                        && !scoreboardTask.isCancelled()
        ) {
            scoreboardTask.cancel();
        }

        ScoreboardManager manager =
                Bukkit.getScoreboardManager();

        if (manager != null) {

            for (Player player :
                    Bukkit.getOnlinePlayers()) {

                player.setScoreboard(
                        manager.getNewScoreboard()
                );
            }
        }

        clearMatchTeams();

        playerBoards.clear();
        lobbyTeams.clear();
    }

    // ========================================================================
    // LOBBY TEAM API
    // ========================================================================

    /**
     * Sets the lobby team of a player.
     *
     * Example:
     *
     * setLobbyTeam(
     *     player.getUniqueId(),
     *     List.of(
     *         player.getUniqueId(),
     *         teammate.getUniqueId()
     *     )
     * );
     *
     * The first player does not have to be the captain.
     * The scoreboard will simply display the members in the supplied order.
     */
    public void setLobbyTeam(
            UUID playerId,
            List<UUID> members
    ) {

        if (playerId == null) {
            return;
        }

        if (
                members == null
                        || members.isEmpty()
        ) {

            lobbyTeams.remove(playerId);
            return;
        }

        List<UUID> cleanMembers =
                new ArrayList<>();

        for (UUID member : members) {

            if (
                    member != null
                            && !cleanMembers.contains(member)
            ) {
                cleanMembers.add(member);
            }
        }

        /*
         * TNTWars teams contain a maximum of 3 players.
         */
        if (cleanMembers.size() > 3) {
            cleanMembers =
                    new ArrayList<>(
                            cleanMembers.subList(0, 3)
                    );
        }

        lobbyTeams.put(
                playerId,
                List.copyOf(cleanMembers)
        );

        /*
         * Store the exact same team for every member.
         *
         * This means asking the scoreboard for any team member
         * gives the same result.
         */
        for (UUID member : cleanMembers) {

            lobbyTeams.put(
                    member,
                    List.copyOf(cleanMembers)
            );
        }
    }

    /**
     * Removes a player from their lobby team.
     */
    public void clearLobbyTeam(UUID playerId) {

        if (playerId == null) {
            return;
        }

        List<UUID> members =
                lobbyTeams.remove(playerId);

        if (members == null) {
            return;
        }

        for (UUID member : members) {

            List<UUID> team =
                    lobbyTeams.get(member);

            if (
                    team != null
                            && team.equals(members)
            ) {
                lobbyTeams.remove(member);
            }
        }
    }

    /**
     * Removes an entire lobby team.
     */
    public void clearLobbyTeam(List<UUID> members) {

        if (members == null) {
            return;
        }

        for (UUID member : members) {

            if (member != null) {
                lobbyTeams.remove(member);
            }
        }
    }

    /**
     * Rebuilds every player's lobby team from a full team snapshot.
     *
     * <p>Called by the lobby server when Velocity publishes the latest team
     * state. Each entry maps every member's UUID to the ordered list of the
     * whole team's members, so all teammates share the same view.</p>
     */
    public void syncLobbyTeams(
            xyz.pyxismc.tournament.common.message.LobbyTeamSyncMessage message
    ) {

        lobbyTeams.clear();

        if (
                message == null
                        || message.teams() == null
        ) {
            return;
        }

        for (
                xyz.pyxismc.tournament.common.message.LobbyTeamSyncMessage.TeamEntry entry :
                message.teams()
        ) {

            if (
                    entry.members() == null
                            || entry.members().isEmpty()
            ) {
                continue;
            }

            /*
             * setLobbyTeam distributes the exact same list to every member,
             * so passing any member as the anchor is enough.
             */
            setLobbyTeam(
                    entry.members().get(0),
                    entry.members()
            );
        }
    }

    /**
     * Returns the lobby team of a player.
     */
    private List<UUID> getLobbyTeam(Player player) {

        if (player == null) {
            return List.of();
        }

        List<UUID> team =
                lobbyTeams.get(
                        player.getUniqueId()
                );

        if (team == null) {
            return List.of();
        }

        return team;
    }

    // ========================================================================
    // MATCH STATE
    // ========================================================================

    public void setMatchSession(
            MatchSession session,
            Map<UUID, RuntimeTeam> runtimeTeams
    ) {

        this.session = session;

        this.finishedSession = null;
        this.winner = null;

        this.runtimeTeams =
                runtimeTeams == null
                        ? Map.of()
                        : Map.copyOf(runtimeTeams);
    }

    public void clearMatchSession() {

        this.session = null;
        this.finishedSession = null;
        this.winner = null;

        this.runtimeTeams = Map.of();

        clearMatchTeams();
    }

    public void updateFinishedScoreboard(
            MatchSession finished,
            String winner
    ) {

        this.finishedSession = finished;
        this.winner = winner;

        this.session = null;

        updateScoreboards();
    }

    // ========================================================================
    // MATCH TEAMS
    // ========================================================================

    public void setMatchTeams(
            List<MatchTeamDef> teams
    ) {

        this.matchTeams =
                teams == null
                        ? List.of()
                        : List.copyOf(teams);

        for (PlayerBoard board :
                playerBoards.values()) {

            applyMatchTeams(board);
        }
    }

    private void applyMatchTeams(
            PlayerBoard board
    ) {

        for (
                int i = 0;
                i < matchTeams.size();
                i++
        ) {

            MatchTeamDef def =
                    matchTeams.get(i);

            String teamName =
                    MATCH_TEAM_PREFIX + i;

            Team team =
                    board.scoreboard()
                            .getTeam(teamName);

            if (team == null) {

                team =
                        board.scoreboard()
                                .registerNewTeam(
                                        teamName
                                );

            } else {

                for (
                        String entry :
                        new ArrayList<>(
                                team.getEntries()
                        )
                ) {
                    team.removeEntry(entry);
                }
            }

            team.setColor(
                    def.color()
            );

            team.setPrefix(
                    def.color()
                            + "["
                            + def.displayName()
                            + "] "
                            + ChatColor.RESET
            );

            for (
                    String member :
                    def.memberNames()
            ) {

                team.addEntry(member);
            }
        }
    }

    private void clearMatchTeams() {

        for (PlayerBoard board :
                playerBoards.values()) {

            for (
                    int i = 0;
                    i < matchTeams.size();
                    i++
            ) {

                Team team =
                        board.scoreboard()
                                .getTeam(
                                        MATCH_TEAM_PREFIX + i
                                );

                if (team != null) {
                    team.unregister();
                }
            }
        }

        matchTeams = List.of();
    }

    // ========================================================================
    // PLAYER SCOREBOARD
    // ========================================================================

    public void applyScoreboardToPlayer(
            Player player
    ) {

        if (player == null) {
            return;
        }

        PlayerBoard board =
                getOrCreateBoard(player);

        if (board != null) {

            player.setScoreboard(
                    board.scoreboard()
            );
        }
    }

    private PlayerBoard getOrCreateBoard(
            Player player
    ) {

        PlayerBoard existing =
                playerBoards.get(
                        player.getUniqueId()
                );

        if (existing != null) {
            return existing;
        }

        ScoreboardManager manager =
                Bukkit.getScoreboardManager();

        if (manager == null) {
            return null;
        }

        Scoreboard scoreboard =
                manager.getNewScoreboard();

        Objective objective =
                scoreboard.registerNewObjective(
                        OBJECTIVE_NAME,
                        "dummy",
                        MiniMessageUtil.deserialize(
                                TITLE
                        )
                );

        objective.setDisplaySlot(
                DisplaySlot.SIDEBAR
        );

        /*
         * HIDE THE RED NUMBERS.
         */
        objective.numberFormat(
                NumberFormat.blank()
        );

        PlayerBoard board =
                new PlayerBoard(
                        scoreboard,
                        objective
                );

        createLines(board);

        playerBoards.put(
                player.getUniqueId(),
                board
        );

        player.setScoreboard(
                scoreboard
        );

        return board;
    }

    // ========================================================================
    // SCOREBOARD LINES
    // ========================================================================

    private void createLines(
            PlayerBoard board
    ) {

        for (
                int line = 1;
                line <= LINE_COUNT;
                line++
        ) {

            createLine(
                    board,
                    line,
                    LINE_ENTRIES[line - 1]
            );
        }

        applyMatchTeams(board);
    }

    private void createLine(
            PlayerBoard board,
            int line,
            String entry
    ) {

        String teamName =
                "line"
                        + String.format(
                        "%02d",
                        line
                );

        Team team =
                board.scoreboard()
                        .registerNewTeam(
                                teamName
                        );

        team.addEntry(entry);

        team.prefix(
                Component.empty()
        );

        /*
         * Score only determines ordering.
         * NumberFormat.blank() makes it invisible.
         */
        board.objective()
                .getScore(entry)
                .setScore(
                        LINE_COUNT - line + 1
                );

        board.teams().put(
                teamName,
                team
        );
    }

    // ========================================================================
    // UPDATE
    // ========================================================================

    private void updateScoreboards() {

        for (
                Player player :
                Bukkit.getOnlinePlayers()
        ) {

            PlayerBoard board =
                    getOrCreateBoard(player);

            if (board == null) {
                continue;
            }

            updatePlayerBoard(
                    player,
                    board
            );
        }

        cleanupOfflineBoards();
    }

    private void updatePlayerBoard(
            Player player,
            PlayerBoard board
    ) {

        if (finishedSession != null) {

            updateFinishedBoard(board);
            return;
        }

        if (session != null) {

            updateMatchBoard(
                    player,
                    board
            );

            return;
        }

        updateLobbyBoard(
                player,
                board
        );
    }

    // ========================================================================
    // LOBBY SCOREBOARD
    //
    // THIS IS THE MAIN SECTION YOU WILL EDIT.
    // ========================================================================

    private void updateLobbyBoard(
            Player player,
            PlayerBoard board
    ) {

        String division =
                getDivision(player);

        List<UUID> team =
                getLobbyTeam(player);

        /*
         * ================================================================
         * LOBBY LAYOUT
         * ================================================================
         *
         * 01  Empty
         *
         * 02  Division » Diamond
         *
         * 03  Empty
         *
         * 04  - <head:player> player
         *     OR
         *     - No Team
         *
         * 05  - <head:player2> player2
         *
         * 06  - <head:player3> player3
         *
         * 07  Empty
         *
         * 08  🌍 Europe (45ms)
         *
         * 09  Empty
         *
         * 10  Empty
         *
         * 11  Empty
         *
         * ================================================================
         */

        String line04;
        String line05 = "";
        String line06 = "";

        /*
         * NO TEAM
         */
        if (team.isEmpty()) {

            line04 =
                    "  "
                            + "<#8693AB>- "
                            + "<#FF5555>No Team";

        } else {

            /*
             * PLAYER 1
             */
            line04 =
                    lobbyMemberLine(
                            team.get(0)
                    );

            /*
             * PLAYER 2
             */
            if (team.size() >= 2) {

                line05 =
                        lobbyMemberLine(
                                team.get(1)
                        );
            }

            /*
             * PLAYER 3
             */
            if (team.size() >= 3) {

                line06 =
                        lobbyMemberLine(
                                team.get(2)
                        );
            }
        }

        /*
         * ================================================================
         * ALL LOBBY LINES ARE TOGETHER HERE.
         * ================================================================
         */
        setLines(
                board,

                // 01
                "",

                // 02
                "  <#BDD4E7>Division "
                        + "<#8693AB>» "
                        + "<#BDD4E7>"
                        + division,

                // 03
                "",

                // 04
                line04,

                // 05
                line05,

                // 06
                line06,

                // 07
                "",

                // 08
                pingLine(player),

                // 09
                "",

                // 10
                "",

                // 11
                ""
        );
    }

    /**
     * Creates a lobby team member line.
     *
     * Example:
     *
     * - <head:IceLoup> IceLoup
     */
    private String lobbyMemberLine(
            UUID playerId
    ) {

        Player member =
                Bukkit.getPlayer(playerId);

        if (member == null) {

            return "  "
                    + "<#8693AB>- "
                    + "<#BDD4E7>Unknown";
        }

        String name =
                member.getName();

        /*
         * The <head:name> tag is deliberately kept here.
         *
         * If MiniMessageUtil supports the custom head tag,
         * the player's head will be displayed.
         */
        return "  "
                + "<#8693AB>- "
                + "<head:"
                + name
                + "> "
                + "<#BDD4E7>"
                + name;
    }

    private String pingLine(
            Player player
    ) {

        return "  "
                + "<"
                + COLOR_GREEN
                + ">🌍 "
                + "<"
                + COLOR_PRIMARY
                + ">Europe "
                + "<"
                + COLOR_SECONDARY
                + ">("
                + "<"
                + COLOR_PRIMARY
                + ">"
                + player.getPing()
                + "ms"
                + "<"
                + COLOR_SECONDARY
                + ">)";
    }

    // ========================================================================
    // MATCH SCOREBOARD
    // ========================================================================

    private void updateMatchBoard(
            Player player,
            PlayerBoard board
    ) {

        MatchSession current =
                session;

        if (current == null) {

            updateLobbyBoard(
                    player,
                    board
            );

            return;
        }

        long remaining =
                remainingSeconds(current);

        long minutes =
                remaining / 60;

        long seconds =
                remaining % 60;

        Map<UUID, String> colors =
                new HashMap<>();

        for (UUID teamId :
                current.teamIds()) {

            RuntimeTeam team =
                    runtimeTeams.get(teamId);

            if (team != null) {

                colors.put(
                        teamId,
                        team.configuredTeam().color()
                );
            }
        }

        UUID roseTeam =
                findTeamByColor(
                        colors,
                        "#FF55FF"
                );

        UUID limeTeam =
                findTeamByColor(
                        colors,
                        "#55FF55"
                );

        UUID aquaTeam =
                findTeamByColor(
                        colors,
                        "#55FFFF"
                );

        int aliveRose =
                aliveCount(
                        current,
                        roseTeam
                );

        int aliveLime =
                aliveCount(
                        current,
                        limeTeam
                );

        int aliveAqua =
                aliveCount(
                        current,
                        aquaTeam
                );

        /*
         * ================================================================
         * MATCH SCOREBOARD
         * ================================================================
         */

        setLines(
                board,

                // 01
                "",

                // 02
                "<#BDD4E7>Time "
                        + "<#8693AB>» "
                        + "<#BDD4E7>"
                        + minutes
                        + "min"
                        + String.format(
                        "%02d",
                        seconds
                )
                        + "s",

                // 03
                "",

                // 04
                "  <#8693AB>- "
                        + ":rose: "
                        + "<#BDD4E7>"
                        + aliveRose,

                // 05
                "  <#8693AB>- "
                        + ":lime: "
                        + "<#BDD4E7>"
                        + aliveLime,

                // 06
                "  <#8693AB>- "
                        + ":aqua: "
                        + "<#BDD4E7>"
                        + aliveAqua,

                // 07
                "",

                // 08
                pingLine(player),

                // 09
                "",

                // 10
                "",

                // 11
                ""
        );
    }

    // ========================================================================
    // FINISHED SCOREBOARD
    // ========================================================================

    private void updateFinishedBoard(
            PlayerBoard board
    ) {

        String winningTeam =
                winner == null
                        ? "Unknown"
                        : winner;

        String line04 = "";
        String line05 = "";
        String line06 = "";

        if (finishedSession != null) {

            List<UUID> teams =
                    new ArrayList<>(
                            finishedSession.teamIds()
                    );

            if (teams.size() >= 1) {

                line04 =
                        createFinishedTeamLine(
                                teams.get(0)
                        );
            }

            if (teams.size() >= 2) {

                line05 =
                        createFinishedTeamLine(
                                teams.get(1)
                        );
            }

            if (teams.size() >= 3) {

                line06 =
                        createFinishedTeamLine(
                                teams.get(2)
                        );
            }
        }

        /*
         * ================================================================
         * FINISHED SCOREBOARD
         * ================================================================
         */

        setLines(
                board,

                // 01
                "",

                // 02
                "<#BDD4E7>Winner "
                        + "<#8693AB>» "
                        + "<#BDD4E7>"
                        + winningTeam,

                // 03
                "",

                // 04
                line04,

                // 05
                line05,

                // 06
                line06,

                // 07
                "",

                // 08
                "",

                // 09
                "",

                // 10
                "",

                // 11
                ""
        );
    }

    private String createFinishedTeamLine(
            UUID teamId
    ) {

        RuntimeTeam team =
                runtimeTeams.get(teamId);

        String name =
                team == null
                        ? "Team"
                        : team.name();

        long kills =
                finishedSession.kills(
                        teamId
                );

        return "<#BDD4E7>"
                + name
                + " "
                + "<#8693AB>K:"
                + "<#BDD4E7>"
                + kills;
    }

    // ========================================================================
    // LINE ENGINE
    // ========================================================================

    /**
     * Updates every scoreboard line.
     *
     * This means the actual scoreboard layout is always visible in the
     * corresponding updateLobbyBoard/updateMatchBoard/updateFinishedBoard
     * method.
     */
    private void setLines(
            PlayerBoard board,
            String... lines
    ) {

        for (
                int i = 0;
                i < LINE_COUNT;
                i++
        ) {

            String value =
                    i < lines.length
                            ? lines[i]
                            : "";

            setLine(
                    board,
                    i + 1,
                    value
            );
        }
    }

    private void setLine(
            PlayerBoard board,
            int line,
            String value
    ) {

        if (
                line < 1
                        || line > LINE_COUNT
        ) {
            return;
        }

        String teamName =
                "line"
                        + String.format(
                        "%02d",
                        line
                );

        Team team =
                board.teams().get(
                        teamName
                );

        if (team == null) {
            return;
        }

        Component component =
                MiniMessageUtil.deserialize(
                        value == null
                                ? ""
                                : value
                );

        team.prefix(component);
    }

    // ========================================================================
    // MATCH UTILITIES
    // ========================================================================

    private int aliveCount(
            MatchSession match,
            UUID teamId
    ) {

        if (teamId == null) {
            return 0;
        }

        return match.aliveCount(teamId);
    }

    private UUID findTeamByColor(
            Map<UUID, String> colors,
            String target
    ) {

        for (
                Map.Entry<UUID, String> entry :
                colors.entrySet()
        ) {

            if (
                    entry.getValue() != null
                            && entry.getValue()
                            .equalsIgnoreCase(target)
            ) {

                return entry.getKey();
            }
        }

        return null;
    }

    private long remainingSeconds(
            MatchSession current
    ) {

        int timeout =
                plugin.getConfig()
                        .getInt(
                                "arena.timeout-seconds",
                                300
                        );

        long elapsed =
                Duration.between(
                        current.startedAt(),
                        Instant.now()
                ).toSeconds();

        return Math.max(
                0,
                timeout - elapsed
        );
    }

    // ========================================================================
    // LOBBY DATA
    // ========================================================================

    private String getDivision(
            Player player
    ) {

        /*
         * Replace this later with your actual division/rank system.
         */
        return "Diamond";
    }

    // ========================================================================
    // CLEANUP
    // ========================================================================

    private void cleanupOfflineBoards() {

        playerBoards.entrySet().removeIf(
                entry -> {

                    Player player =
                            Bukkit.getPlayer(
                                    entry.getKey()
                            );

                    if (
                            player != null
                                    && player.isOnline()
                    ) {
                        return false;
                    }

                    PlayerBoard board =
                            entry.getValue();

                    for (
                            Team team :
                            board.scoreboard()
                                    .getTeams()
                    ) {

                        team.unregister();
                    }

                    return true;
                }
        );
    }

    // ========================================================================
    // PLAYER BOARD
    // ========================================================================

    private static final class PlayerBoard {

        private final Scoreboard scoreboard;
        private final Objective objective;

        private final Map<String, Team> teams =
                new HashMap<>();

        private PlayerBoard(
                Scoreboard scoreboard,
                Objective objective
        ) {

            this.scoreboard = scoreboard;
            this.objective = objective;
        }

        private Scoreboard scoreboard() {
            return scoreboard;
        }

        private Objective objective() {
            return objective;
        }

        private Map<String, Team> teams() {
            return teams;
        }
    }
}