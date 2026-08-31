package xyz.pyxismc.tournament.paper.match;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scheduler.BukkitTask;

import xyz.pyxismc.tournament.paper.message.MiniMessageUtil;

/**
 * Handles the scoreboard for both lobby and match states.
 */
public final class ScoreboardHandler {

    private final JavaPlugin plugin;
    private volatile MatchSession session;
    private Map<UUID, RuntimeTeam> runtimeTeams = Map.of();
    private MatchSession finishedSession;
    private String winner;
    private Scoreboard scoreboard;
    private BukkitTask scoreboardTask;

    public ScoreboardHandler(JavaPlugin plugin) {
        this.plugin = plugin;
        startScoreboardTask();
    }

    private void startScoreboardTask() {
        this.scoreboardTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateScoreboard, 0L, 20L);
    }

    public void stopScoreboardTask() {
        if (this.scoreboardTask != null && !this.scoreboardTask.isCancelled()) {
            this.scoreboardTask.cancel();
        }
    }

    /**
     * Sets the current match data for the scoreboard to display match information.
     */
    public void setMatchSession(MatchSession session, Map<UUID, RuntimeTeam> runtimeTeams) {
        this.session = session;
        this.runtimeTeams = runtimeTeams;
    }

    /**
     * Clears the match data, causing the scoreboard to display lobby information.
     */
    public void clearMatchSession() {
        this.session = null;
        this.finishedSession = null;
        this.winner = null;
        this.runtimeTeams = Map.of();
    }

    /**
     * Marks the match as finished so the scoreboard keeps showing the final
     * results until {@link #clearMatchSession()} is called.
     */
    public void updateFinishedScoreboard(MatchSession finished, String winner) {
        this.finishedSession = finished;
        this.winner = winner;
        updateScoreboard();
    }

    /**
     * Gets the scoreboard, initializing it if necessary.
     * @return the scoreboard
     */
    public Scoreboard getScoreboard() {
        if (this.scoreboard == null) {
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return null; // will be initialized later by the update task
            }
            this.scoreboard = manager.getNewScoreboard();
            this.scoreboard.registerNewObjective(
                    "tntwars",
                    "dummy",
                    MiniMessageUtil.deserialize("<gradient:#8693AB:#BDD4E7>ᴛɴᴛᴡᴀʀꜱ</gradient>")
            ).setDisplaySlot(DisplaySlot.SIDEBAR);

            // Apply to all online players after initialization
            for (Player player : Bukkit.getOnlinePlayers()) {
                applyScoreboardToPlayer(player);
            }
        }
        return this.scoreboard;
    }

    /**
     * Applies this scoreboard to the given player.
     */
    public void applyScoreboardToPlayer(Player player) {
        Scoreboard sb = getScoreboard();
        if (sb != null) {
            player.setScoreboard(sb);
        }
    }

    /**
     * Updates the scoreboard for all online players.
     * This method is called by the repeating task.
     */
    private void updateScoreboard() {
        // Lazy initialization of scoreboard
        if (this.scoreboard == null) {
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return; // try again next tick
            }
            this.scoreboard = manager.getNewScoreboard();
            this.scoreboard.registerNewObjective(
                    "tntwars",
                    "dummy",
                    MiniMessageUtil.deserialize("<gradient:#8693AB:#BDD4E7>ᴛɴᴛᴡᴀʀꜱ</gradient>")
            ).setDisplaySlot(DisplaySlot.SIDEBAR);

            // Apply to all online players after initialization
            for (Player player : Bukkit.getOnlinePlayers()) {
                applyScoreboardToPlayer(player);
            }
        }

        Objective objective = this.scoreboard.getObjective("tntwars");
        if (objective == null) {
            return;
        }

        clearSidebar();
        int score = 15;

        // Set the title (gradient) - already set in initialization, but we ensure it's set
        objective.displayName(
                MiniMessageUtil.deserialize("<gradient:#8693AB:#BDD4E7>ᴛɴᴛᴡᴀʀꜱ</gradient>")
        );

        // Empty line
        addLine(objective, "", score--);

        if (this.finishedSession != null) {
            score = updateFinishedScoreboardLines(objective, score);
        } else if (this.session != null) {
            // Match scoreboard
            score = updateMatchScoreboard(objective, score);
        } else {
            // Lobby scoreboard
            score = updateLobbyScoreboard(objective, score);
        }

        // Empty line
        addLine(objective, "", score--);
    }

    private int updateFinishedScoreboardLines(Objective objective, int score) {
        MatchSession finished = this.finishedSession;
        String winner = this.winner == null ? "none" : this.winner;
        if (finished == null) {
            return score;
        }

        // Finished line
        addLine(objective, MiniMessageUtil.deserialize(
                "<#BDD4E7>Finished<#8693AB>"
        ), score--);

        // Winner line
        addLine(objective, MiniMessageUtil.deserialize(
                "<#BDD4E7>Winner: <#8693AB>" + winner
        ), score--);

        // Empty line
        addLine(objective, ChatColor.DARK_GRAY + " ", score--);

        for (UUID teamId : finished.teamIds()) {
            RuntimeTeam team = this.runtimeTeams.get(teamId);
            ChatColor color = team == null ? ChatColor.WHITE : toChatColor(team.configuredTeam().color());
            String name = team == null ? teamId.toString().substring(0, 8) : team.name();
            addLine(objective, color + name + ChatColor.WHITE + " K:" + finished.kills(teamId), score--);
        }
        return score;
    }

    private int updateMatchScoreboard(Objective objective, int score) {
        MatchSession current = this.session;
        if (current == null) {
            return score;
        }

        // Time line
        String timeLine = "<#BDD4E7>Time<#8693AB> ><#BDD4E7> " + remainingSeconds(current);
        addLine(objective, MiniMessageUtil.deserialize(timeLine), score--);

        // Empty line
        addLine(objective, "", score--);

        // Team lines - we need to map team IDs to colors
        Map<UUID, String> teamIdToColor = new java.util.HashMap<>();
        for (UUID teamId : current.teamIds()) {
            RuntimeTeam team = this.runtimeTeams.get(teamId);
            if (team != null) {
                teamIdToColor.put(teamId, team.configuredTeam().color());
            }
        }

        // Rose team (Rose Pastel: #FF55FF)
        UUID roseTeamId = findTeamIdByColor(teamIdToColor, "#FF55FF");
        int aliveRose = roseTeamId != null ? current.aliveCount(roseTeamId) : 0;
        String roseLine = ":rose:<#BDD4E7> " + aliveRose;
        addLine(objective, MiniMessageUtil.deserialize(roseLine), score--);

        // Aqua team (Aqua: #55FFFF)
        UUID aquaTeamId = findTeamIdByColor(teamIdToColor, "#55FFFF");
        int aliveAqua = aquaTeamId != null ? current.aliveCount(aquaTeamId) : 0;
        String aquaLine = ":aqua:<#BDD4E7> " + aliveAqua;
        addLine(objective, MiniMessageUtil.deserialize(aquaLine), score--);

        // Lime team (Lime: #55FF55)
        UUID limeTeamId = findTeamIdByColor(teamIdToColor, "#55FF55");
        int aliveLime = limeTeamId != null ? current.aliveCount(limeTeamId) : 0;
        String limeLine = ":lime:<#BDD4E7> " + aliveLime;
        addLine(objective, MiniMessageUtil.deserialize(limeLine), score--);

        // Empty line
        addLine(objective, "", score--);

        // PING line - show average ping of online players
        double avgPing = 0;
        int playerCount = Bukkit.getOnlinePlayers().size();
        if (playerCount > 0) {
            int totalPing = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                totalPing += player.getPing();
            }
            avgPing = totalPing / (double) playerCount;
        }
        String pingLine = "<#BDD4E7>🌍  EU<#8693AB> (<#BDD4E7>" + Math.round(avgPing) + "ms<#8693AB>)";
        addLine(objective, MiniMessageUtil.deserialize(pingLine), score--);
        return score;
    }

    private int updateLobbyScoreboard(Objective objective, int score) {
        // Division line
        String division = getDivision(); // TODO: replace with actual lobby data
        String divisionLine = "<#BDD4E7>Division<#8693AB> > :division:".replace(":division:", division);
        addLine(objective, MiniMessageUtil.deserialize(divisionLine), score--);

        // Empty line
        addLine(objective, "", score--);

        // Captain line
        UUID captainUUID = getCaptainUUID(); // TODO
        String captainName = getCaptainName(); // TODO
        String captainLine = "- <head:uuid}> {captain}"
                .replace("uuid", captainUUID.toString())
                .replace("{captain}", captainName);
        addLine(objective, MiniMessageUtil.deserialize(captainLine), score--);

        // Player lines: two players
        java.util.List<UUID> playerUUIDs = getPlayerUUIDs(); // TODO: should return a list of at least 2 uuids
        java.util.List<String> playerNames = getPlayerNames(); // TODO
        for (int i = 0; i < 2; i++) {
            if (i < playerUUIDs.size()) {
                UUID playerUUID = playerUUIDs.get(i);
                String playerName = playerNames.get(i);
                String playerLine = "- <head:uuid> {player}"
                        .replace("uuid", playerUUID.toString())
                        .replace("{player}", playerName);
                addLine(objective, MiniMessageUtil.deserialize(playerLine), score--);
            } else {
                // Not enough players, show a placeholder
                addLine(objective, "- ", score--);
            }
        }

        // Empty line
        addLine(objective, "", score--);

        // PING line - show average ping of online players
        double avgPing = 0;
        int playerCount = Bukkit.getOnlinePlayers().size();
        if (playerCount > 0) {
            int totalPing = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                totalPing += player.getPing();
            }
            avgPing = totalPing / (double) playerCount;
        }
        String pingLine = "<#BDD4E7>🌍 EU<#8693AB> (<#BDD4E7>" + Math.round(avgPing) + "ms<#8693AB>)";
        addLine(objective, MiniMessageUtil.deserialize(pingLine), score--);
        return score;
    }

    private void clearSidebar() {
        for (String entry : new java.util.ArrayList<>(this.scoreboard.getEntries())) {
            this.scoreboard.resetScores(entry);
        }
    }

    private static void addLine(Objective objective, Component component, int score) {
        String text = LegacyComponentSerializer.legacySection().serialize(component);
        addLine(objective, text, score);
    }

    private static void addLine(Objective objective, String text, int score) {
        objective.getScore(text + ChatColor.values()[Math.max(0, Math.min(15, score))]).setScore(score);
    }

    private int remainingSeconds(MatchSession current) {
        int timeout = plugin.getConfig().getInt("arena.timeout-seconds", 300);
        long elapsed = java.time.Duration.between(current.startedAt(), java.time.Instant.now()).toSeconds();
        return (int) Math.max(0, timeout - elapsed);
    }

    private UUID findTeamIdByColor(Map<UUID, String> teamIdToColor, String targetColorHex) {
        for (java.util.Map.Entry<UUID, String> entry : teamIdToColor.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(targetColorHex)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static ChatColor toChatColor(String hexColor) {
        return switch (hexColor.toLowerCase(Locale.ROOT)) {
            case "#55ffff" -> ChatColor.AQUA;
            case "#ff55ff" -> ChatColor.LIGHT_PURPLE;
            case "#55ff55" -> ChatColor.GREEN;
            default -> ChatColor.WHITE;
        };
    }

    // Lobby data stub methods - replace with actual lobby data source
    private String getDivision() {
        return "Diamond"; // TODO: replace with actual lobby data
    }

    private UUID getCaptainUUID() {
        // Return the UUID of the first online player, or a random UUID if none
        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        if (!players.isEmpty()) {
            return players.iterator().next().getUniqueId();
        }
        return java.util.UUID.randomUUID(); // placeholder
    }

    private String getCaptainName() {
        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        if (!players.isEmpty()) {
            return players.iterator().next().getName();
        }
        return "Captain"; // TODO
    }

    private java.util.List<UUID> getPlayerUUIDs() {
        java.util.List<UUID> uuids = new java.util.ArrayList<>();
        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        java.util.Iterator<? extends Player> it = players.iterator();
        int count = 0;
        while (it.hasNext() && count < 2) {
            uuids.add(it.next().getUniqueId());
            count++;
        }
        return uuids;
    }

    private java.util.List<String> getPlayerNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        java.util.Iterator<? extends Player> it = players.iterator();
        int count = 0;
        while (it.hasNext() && count < 2) {
            names.add(it.next().getName());
            count++;
        }
        return names;
    }
}