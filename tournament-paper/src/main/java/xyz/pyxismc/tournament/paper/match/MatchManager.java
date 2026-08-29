package xyz.pyxismc.tournament.paper.match;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

import xyz.pyxismc.tournament.common.message.JsonCodec;
import xyz.pyxismc.tournament.common.message.MatchReadyMessage;
import xyz.pyxismc.tournament.common.message.MatchResultMessage;
import xyz.pyxismc.tournament.common.message.MatchStartMessage;
import xyz.pyxismc.tournament.common.message.MessageChannels;
import xyz.pyxismc.tournament.common.redis.TournamentRedis;
import xyz.pyxismc.tournament.common.result.MatchResult;
import xyz.pyxismc.tournament.paper.message.MiniMessageUtil;

public final class MatchManager implements Listener {

    private static final String ARENA_TIMEOUT_KEY = "arena.timeout-seconds";
    private static final String ARENA_WORLD_KEY = "arena.world";
    private static final String ARENA_TEAMS_KEY = "arena.teams";
    private static final String ARENA_SPAWNS_KEY = "arena.spawns";
    private static final String EXPLOSION_SECTION_KEY = "arena.explosion";
    private static final String EXPLOSION_POWER_KEY = "power";
    private static final String EXPLOSION_FIRE_KEY = "fire";
    private static final String EXPLOSION_BLOCKS_KEY = "break-blocks";
    private static final String FINISH_DELAY_KEY = "finish.shutdown-delay-seconds";
    private static final String FINISH_LOBBY_KEY = "finish.lobby-server";
    private static final String BUNGEE_CHANNEL = "BungeeCord";

    private static final List<TeamColor> DEFAULT_COLORS = List.of(
            new TeamColor("aqua", "Aqua", "#55FFFF"),
            new TeamColor("rose_pastel", "Rose Pastel", "#FF55FF"),
            new TeamColor("lime", "Lime", "#55FF55"));

    private final JavaPlugin plugin;
    private final TournamentRedis redis;
    private final JsonCodec codec;
    private final Map<TntBlockKey, Location> pendingTntSnaps = new HashMap<>();

    private volatile MatchSession session;
    private Map<UUID, RuntimeTeam> runtimeTeams = Map.of();
    private Scoreboard scoreboard;
    private String tournamentName = "Tournament";
    private int timeoutTaskId = -1;
    private int scoreboardTaskId = -1;
    private int shutdownTaskId = -1;

    public MatchManager(JavaPlugin plugin, TournamentRedis redis, JsonCodec codec) {
        this.plugin = plugin;
        this.redis = redis;
        this.codec = codec;
    }

    public synchronized void startMatch(MatchStartMessage message) {
        if (this.session != null) {
            this.plugin.getLogger().warning("A match is already running, ignoring match " + message.matchId());
            return;
        }

        Map<UUID, UUID> playerToTeam = new LinkedHashMap<>();
        for (UUID teamId : message.teamIds()) {
            for (UUID playerId : message.playersByTeam().getOrDefault(teamId, List.of())) {
                playerToTeam.put(playerId, teamId);
            }
        }

        MatchSession newSession = new MatchSession(
                message.matchId(), message.serverId(), playerToTeam, Instant.now());
        this.session = newSession;
        this.tournamentName = message.tournamentName();

        MiniMessageUtil.setServerName(message.serverId());
        MiniMessageUtil.setTournamentName(message.tournamentName());

        World world = resolveWorld();
        applyArenaRules(world);
        List<ConfiguredTeam> configuredTeams = resolveTeamSlots(world);
        setupScoreboard(message, configuredTeams);

        for (int teamIndex = 0; teamIndex < message.teamIds().size(); teamIndex++) {
            UUID teamId = message.teamIds().get(teamIndex);
            ConfiguredTeam configuredTeam = configuredTeams.get(Math.min(teamIndex, configuredTeams.size() - 1));
            List<Location> spawns = configuredTeam.spawns();
            List<UUID> players = message.playersByTeam().getOrDefault(teamId, List.of());
            for (int playerIndex = 0; playerIndex < players.size(); playerIndex++) {
                UUID playerId = players.get(playerIndex);
                Player player = Bukkit.getPlayer(playerId);
                if (player == null) {
                    newSession.onPlayerQuit(playerId);
                    continue;
                }
                Location spawn = spawns.get(Math.min(playerIndex, spawns.size() - 1));
                player.setGameMode(GameMode.SURVIVAL);
                player.teleport(spawn);
                giveKit(player);
                player.setScoreboard(this.scoreboard);
                MiniMessageUtil.send(player,
                        "<" + configuredTeam.color() + ">Match " + message.tournamentName()
                                + " started. Last team standing wins!");
            }
        }

        this.timeoutTaskId = Bukkit.getScheduler().runTaskLater(this.plugin, this::onTimeout,
                (long) this.plugin.getConfig().getInt(ARENA_TIMEOUT_KEY, 300) * 20L).getTaskId();
        this.scoreboardTaskId = Bukkit.getScheduler().runTaskTimer(this.plugin, this::updateScoreboard,
                0L, 20L).getTaskId();

        this.plugin.getLogger().info("Match " + message.matchId() + " started on " + message.serverId());
        this.redis.publish(MessageChannels.MATCH_READY, this.codec.toJson(
                new MatchReadyMessage(message.matchId(), message.serverId())));
    }

    public synchronized void cancelSession() {
        if (this.session == null) {
            return;
        }
        this.session = null;
        cancelTasks();
        MiniMessageUtil.setServerName("Unknown");
        MiniMessageUtil.setTournamentName("Tournament");
        this.plugin.getLogger().info("Match session cancelled");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (this.session == null || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!this.session.isMatchPlayer(victim.getUniqueId())) {
            return;
        }
        UUID attackerId = attackerId(event.getDamager());
        if (attackerId != null) {
            UUID victimTeam = this.session.teamOf(victim.getUniqueId());
            UUID attackerTeam = this.session.teamOf(attackerId);
            if (attackerTeam != null && attackerTeam.equals(victimTeam)) {
                event.setCancelled(true);
                return;
            }
            this.session.recordAttacker(victim.getUniqueId(), attackerId);
        }
        if (!event.isCancelled()) {
            this.session.recordDamage(victim.getUniqueId(), event.getFinalDamage());
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (this.session == null) {
            return;
        }
        Player victim = event.getEntity();
        if (!this.session.isMatchPlayer(victim.getUniqueId()) || !this.session.isAlive(victim.getUniqueId())) {
            return;
        }
        event.setDeathMessage(null);
        UUID killerId = victim.getKiller() == null ? null : victim.getKiller().getUniqueId();
        this.session.onPlayerDeath(victim.getUniqueId(), killerId);
        Bukkit.getScheduler().runTask(this.plugin, () -> victim.setGameMode(GameMode.SPECTATOR));
        victim.sendMessage(MiniMessageUtil.error("You are out of the match."));
        updateScoreboard();
        if (this.session.isOver()) {
            endMatch();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (this.session == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!this.session.isMatchPlayer(player.getUniqueId())) {
            return;
        }
        this.session.onPlayerQuit(player.getUniqueId());
        updateScoreboard();
        if (this.session.isOver()) {
            endMatch();
        }
    }

    @EventHandler
    public void onTntPrime(TNTPrimeEvent event) {
        if (this.session == null || event.isCancelled()) {
            return;
        }
        Block block = event.getBlock();
        this.pendingTntSnaps.put(TntBlockKey.of(block), centerOf(block));
        Bukkit.getScheduler().runTaskLater(this.plugin,
                () -> this.pendingTntSnaps.remove(TntBlockKey.of(block)), 2L);
    }

    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (this.session == null || !(event.getEntity() instanceof TNTPrimed tnt)) {
            return;
        }
        Location target = snapLocation(tnt.getLocation());
        tnt.teleport(target);
        tnt.setVelocity(new Vector(0.0, 0.0, 0.0));
    }

    @EventHandler(ignoreCancelled = true)
    public void onTntPlace(BlockPlaceEvent event) {
        if (this.session == null || event.getBlockPlaced().getType() != Material.TNT) {
            return;
        }
        Player player = event.getPlayer();
        if (!this.session.isMatchPlayer(player.getUniqueId())) {
            return;
        }
        Bukkit.getScheduler().runTask(this.plugin,
                () -> player.getInventory().addItem(new ItemStack(Material.TNT, 1)));
    }

    @EventHandler
    public void onShootBow(EntityShootBowEvent event) {
        if (this.session == null || !(event.getEntity() instanceof Player shooter)) {
            return;
        }
        if (this.session.isMatchPlayer(shooter.getUniqueId())) {
            shooter.getInventory().addItem(new ItemStack(Material.ARROW, 1));
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (this.session == null) {
            return;
        }
        Projectile projectile = event.getEntity();
        if (!(projectile instanceof Arrow) || !(projectile.getShooter() instanceof Player shooter)) {
            return;
        }
        if (!this.session.isMatchPlayer(shooter.getUniqueId())) {
            return;
        }
        Location hit = event.getHitBlock() != null
                ? event.getHitBlock().getLocation().add(0.5, 0.5, 0.5)
                : event.getHitEntity() != null ? event.getHitEntity().getLocation() : projectile.getLocation();
        org.bukkit.configuration.ConfigurationSection explosion = this.plugin.getConfig()
                .getConfigurationSection(EXPLOSION_SECTION_KEY);
        float power = (float) (explosion == null ? 3.0 : explosion.getDouble(EXPLOSION_POWER_KEY, 3.0));
        boolean fire = explosion == null || explosion.getBoolean(EXPLOSION_FIRE_KEY, false);
        boolean breakBlocks = explosion == null || explosion.getBoolean(EXPLOSION_BLOCKS_KEY, false);
        hit.getWorld().createExplosion(hit, power, fire, breakBlocks);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (this.session != null && this.session.isMatchPlayer(event.getPlayer().getUniqueId())) {
            RuntimeTeam team = this.runtimeTeams.get(this.session.teamOf(event.getPlayer().getUniqueId()));
            Location respawn = team == null ? event.getRespawnLocation().getWorld().getSpawnLocation()
                    : team.configuredTeam().spawns().getFirst();
            event.setRespawnLocation(respawn);
            if (!this.session.isAlive(event.getPlayer().getUniqueId())) {
                Bukkit.getScheduler().runTask(this.plugin, () -> event.getPlayer().setGameMode(GameMode.SPECTATOR));
            }
        }
    }

    private void onTimeout() {
        if (this.session == null) {
            return;
        }
        this.plugin.getLogger().info("Match " + this.session.matchId() + " timed out, deciding winner");
        this.session.finishByTimeout();
        endMatch();
    }

    private synchronized void endMatch() {
        if (this.session == null) {
            return;
        }
        MatchSession finished = this.session;
        this.session = null;
        Bukkit.getScheduler().cancelTask(this.timeoutTaskId);
        Bukkit.getScheduler().cancelTask(this.scoreboardTaskId);
        MatchResult result = finished.buildResult(finished.matchId());
        this.redis.publish(MessageChannels.MATCH_RESULT, this.codec.toJson(
                new MatchResultMessage(finished.matchId(), finished.serverId(), result)));
        String winner = winnerName(finished);
        this.plugin.getLogger().info("Match " + finished.matchId() + " finished, winner " + winner);
        Bukkit.broadcast(MiniMessageUtil.deserialize(
                MiniMessageUtil.primary("Match finished. Winner team: ") + winner));
        updateFinishedScoreboard(finished, winner);
        MiniMessageUtil.setServerName("Unknown");
        MiniMessageUtil.setTournamentName("Tournament");
        scheduleLobbyReturnAndShutdown();
    }

    private World resolveWorld() {
        String worldName = this.plugin.getConfig().getString(ARENA_WORLD_KEY, "world");
        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            return world;
        }
        this.plugin.getLogger().warning("Arena world '" + worldName
                + "' not found, generating an empty arena world");
        World created = Bukkit.createWorld(new WorldCreator(worldName).generator(new VoidChunkGenerator()));
        if (created == null) {
            return Bukkit.getWorlds().getFirst();
        }
        List<ConfiguredTeam> teams = resolveTeamSlots(created);
        for (ConfiguredTeam team : teams) {
            for (Location spawn : team.spawns()) {
                createPlatform(created, spawn);
            }
        }
        if (!teams.isEmpty() && !teams.getFirst().spawns().isEmpty()) {
            Location first = teams.getFirst().spawns().getFirst();
            created.setSpawnLocation(first.getBlockX(), first.getBlockY(), first.getBlockZ());
        }
        return created;
    }

    private static void createPlatform(World world, Location spawn) {
        int x = spawn.getBlockX();
        int y = spawn.getBlockY() - 1;
        int z = spawn.getBlockZ();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.getBlockAt(x + dx, y, z + dz).setType(Material.OBSIDIAN);
            }
        }
    }

    private static final class VoidChunkGenerator extends ChunkGenerator {
    }

    private List<ConfiguredTeam> resolveTeamSlots(World world) {
        List<ConfiguredTeam> teams = new ArrayList<>();
        List<Map<?, ?>> configured = this.plugin.getConfig().getMapList(ARENA_TEAMS_KEY);
        for (int index = 0; index < configured.size(); index++) {
            Map<?, ?> raw = configured.get(index);
            TeamColor color = colorOf(text(raw, "color", DEFAULT_COLORS.get(index % DEFAULT_COLORS.size()).key()));
            String displayName = text(raw, "display-name", color.displayName());
            List<Location> spawns = parseSpawns(world, raw.get("spawns"));
            if (spawns.isEmpty()) {
                spawns = List.of(world.getSpawnLocation());
            }
            teams.add(new ConfiguredTeam(color.key(), displayName, color.chatColor(), spawns));
        }
        if (!teams.isEmpty()) {
            return teams;
        }
        List<Location> legacySpawns = resolveLegacySpawns(world);
        for (int i = 0; i < Math.max(DEFAULT_COLORS.size(), legacySpawns.size()); i++) {
            TeamColor color = DEFAULT_COLORS.get(i % DEFAULT_COLORS.size());
            Location spawn = legacySpawns.get(Math.min(i, legacySpawns.size() - 1));
            teams.add(new ConfiguredTeam(color.key(), color.displayName(), color.chatColor(), List.of(spawn)));
        }
        return teams;
    }

    private List<Location> resolveLegacySpawns(World world) {
        List<Location> spawns = new ArrayList<>();
        for (Map<?, ?> raw : this.plugin.getConfig().getMapList(ARENA_SPAWNS_KEY)) {
            spawns.add(location(world, raw));
        }
        return spawns.isEmpty() ? List.of(world.getSpawnLocation()) : spawns;
    }

    private static List<Location> parseSpawns(World world, Object rawSpawns) {
        if (!(rawSpawns instanceof List<?> list)) {
            return List.of();
        }
        List<Location> spawns = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> spawn) {
                spawns.add(location(world, spawn));
            }
        }
        return spawns;
    }

    private static Location location(World world, Map<?, ?> map) {
        double x = number(map, "x", 0.0);
        double y = number(map, "y", 64.0);
        double z = number(map, "z", 0.0);
        float yaw = (float) number(map, "yaw", 0.0);
        float pitch = (float) number(map, "pitch", 0.0);
        return new Location(world, x, y, z, yaw, pitch);
    }

    private static double number(Map<?, ?> map, String key, double fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private static String text(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private void applyArenaRules(World world) {
        world.setGameRule(GameRule.FALL_DAMAGE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        world.setGameRule(GameRule.WATER_SOURCE_CONVERSION, false);
    }

    private void setupScoreboard(MatchStartMessage message, List<ConfiguredTeam> configuredTeams) {
        this.scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = this.scoreboard.registerNewObjective("tntwars", "dummy",
                Component.text("TNTWars").color(NamedTextColor.GOLD));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        Map<UUID, RuntimeTeam> teams = new LinkedHashMap<>();
        for (int index = 0; index < message.teamIds().size(); index++) {
            UUID teamId = message.teamIds().get(index);
            ConfiguredTeam configuredTeam = configuredTeams.get(Math.min(index, configuredTeams.size() - 1));
            String teamName = message.teamNames().getOrDefault(teamId, configuredTeam.displayName());
            Team scoreboardTeam = this.scoreboard.registerNewTeam("tw" + index);
            scoreboardTeam.setColor(toChatColor(configuredTeam.color()));
            scoreboardTeam.setPrefix(toChatColor(configuredTeam.color()) + "[" + teamName + "] " + ChatColor.RESET);
            for (UUID playerId : message.playersByTeam().getOrDefault(teamId, List.of())) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    scoreboardTeam.addEntry(player.getName());
                }
            }
            teams.put(teamId, new RuntimeTeam(teamName, configuredTeam, scoreboardTeam));
        }
        this.runtimeTeams = teams;
    }

    private static ChatColor toChatColor(String hexColor) {
        return switch (hexColor.toLowerCase()) {
            case "#55ffff" -> ChatColor.AQUA;
            case "#ff55ff" -> ChatColor.LIGHT_PURPLE;
            case "#55ff55" -> ChatColor.GREEN;
            default -> ChatColor.WHITE;
        };
    }

    private void updateScoreboard() {
        MatchSession current = this.session;
        if (current == null || this.scoreboard == null) {
            return;
        }
        Objective objective = this.scoreboard.getObjective("tntwars");
        if (objective == null) {
            return;
        }
        clearSidebar();
        int score = 15;
        addLine(objective, ChatColor.WHITE + this.tournamentName, score--);
        addLine(objective, ChatColor.GRAY + "Time: " + remainingSeconds(current) + "s", score--);
        addLine(objective, ChatColor.DARK_GRAY + " ", score--);
        for (UUID teamId : current.teamIds()) {
            RuntimeTeam team = this.runtimeTeams.get(teamId);
            ChatColor color = team == null ? ChatColor.WHITE : toChatColor(team.configuredTeam().color());
            String name = team == null ? teamId.toString().substring(0, 8) : team.name();
            addLine(objective, color + name + ChatColor.WHITE + " "
                    + current.aliveCount(teamId) + "/3 "
                    + ChatColor.GRAY + "K:" + current.kills(teamId), score--);
        }
    }

    private void updateFinishedScoreboard(MatchSession finished, String winner) {
        if (this.scoreboard == null) {
            return;
        }
        Objective objective = this.scoreboard.getObjective("tntwars");
        if (objective == null) {
            return;
        }
        clearSidebar();
        int score = 15;
        addLine(objective, ChatColor.GOLD + "Finished", score--);
        addLine(objective, ChatColor.WHITE + "Winner: " + winner, score--);
        addLine(objective, ChatColor.DARK_GRAY + " ", score--);
        for (UUID teamId : finished.teamIds()) {
            RuntimeTeam team = this.runtimeTeams.get(teamId);
            ChatColor color = team == null ? ChatColor.WHITE : toChatColor(team.configuredTeam().color());
            String name = team == null ? teamId.toString().substring(0, 8) : team.name();
            addLine(objective, color + name + ChatColor.WHITE + " K:" + finished.kills(teamId), score--);
        }
    }

    private void clearSidebar() {
        for (String entry : new ArrayList<>(this.scoreboard.getEntries())) {
            this.scoreboard.resetScores(entry);
        }
    }

    private static void addLine(Objective objective, String text, int score) {
        objective.getScore(text + ChatColor.values()[Math.max(0, Math.min(15, score))]).setScore(score);
    }

    private int remainingSeconds(MatchSession current) {
        int timeout = this.plugin.getConfig().getInt(ARENA_TIMEOUT_KEY, 300);
        long elapsed = java.time.Duration.between(current.startedAt(), Instant.now()).toSeconds();
        return (int) Math.max(0, timeout - elapsed);
    }

    private String winnerName(MatchSession finished) {
        return finished.winnerTeamId()
                .map(teamId -> {
                    RuntimeTeam team = this.runtimeTeams.get(teamId);
                    if (team == null) {
                        return teamId.toString();
                    }
                    String color = team.configuredTeam().color();
                    return "<" + color + ">" + team.name() + "</" + color + ">";
                })
                .orElse("none");
    }

    private void giveKit(Player player) {
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        org.bukkit.configuration.ConfigurationSection armor = this.plugin.getConfig()
                .getConfigurationSection("kit.armor");
        if (armor != null) {
            inventory.setHelmet(item(armor.getString("helmet"), 1, null));
            inventory.setChestplate(item(armor.getString("chestplate"), 1, null));
            inventory.setLeggings(item(armor.getString("leggings"), 1, null));
            inventory.setBoots(item(armor.getString("boots"), 1, null));
        }

        for (Map<?, ?> raw : this.plugin.getConfig().getMapList("kit.inventory")) {
            int slot = (int) number(raw, "slot", -1);
            if (slot < 0 || slot >= inventory.getSize()) {
                continue;
            }
            String material = text(raw, "material", "AIR");
            int amount = Math.max(1, Math.min(64, (int) number(raw, "amount", 1)));
            inventory.setItem(slot, item(material, amount, raw.get("enchantments")));
        }
        player.updateInventory();
    }

    private ItemStack item(String materialName, int amount, Object enchantments) {
        if (materialName == null) {
            return null;
        }
        Material material = Material.matchMaterial(materialName);
        if (material == null || material == Material.AIR) {
            return null;
        }
        ItemStack stack = new ItemStack(material, amount);
        if (enchantments instanceof Map<?, ?> map && !map.isEmpty()) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Enchantment enchantment = Enchantment.getByKey(NamespacedKey.minecraft(
                            String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT)));
                    if (enchantment != null && entry.getValue() instanceof Number level) {
                        meta.addEnchant(enchantment, Math.max(1, level.intValue()), true);
                    }
                }
                stack.setItemMeta(meta);
            }
        }
        return stack;
    }

    private Location snapLocation(Location spawned) {
        TntBlockKey key = TntBlockKey.of(spawned);
        Location target = this.pendingTntSnaps.remove(key);
        if (target != null) {
            return target;
        }
        return new Location(spawned.getWorld(),
                spawned.getBlockX() + 0.5,
                spawned.getBlockY(),
                spawned.getBlockZ() + 0.5,
                spawned.getYaw(),
                spawned.getPitch());
    }

    private static Location centerOf(Block block) {
        return new Location(block.getWorld(),
                block.getX() + 0.5,
                block.getY(),
                block.getZ() + 0.5);
    }

    private static UUID attackerId(org.bukkit.entity.Entity damager) {
        if (damager instanceof Player player) {
            return player.getUniqueId();
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player.getUniqueId();
        }
        return null;
    }

    private void scheduleLobbyReturnAndShutdown() {
        int delaySeconds = this.plugin.getConfig().getInt(FINISH_DELAY_KEY, 5);
        this.shutdownTaskId = Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            String lobby = this.plugin.getConfig().getString(FINISH_LOBBY_KEY, "lobby");
            for (Player player : Bukkit.getOnlinePlayers()) {
                sendToLobby(player, lobby);
            }
            Bukkit.getScheduler().runTaskLater(this.plugin, Bukkit::shutdown, 20L);
        }, Math.max(0, delaySeconds) * 20L).getTaskId();
    }

    private void sendToLobby(Player player, String lobby) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("Connect");
            out.writeUTF(lobby);
            player.sendPluginMessage(this.plugin, BUNGEE_CHANNEL, bytes.toByteArray());
        } catch (IOException e) {
            this.plugin.getLogger().warning("Could not send " + player.getName() + " to lobby: " + e.getMessage());
        }
    }

    private void cancelTasks() {
        if (this.timeoutTaskId != -1) {
            Bukkit.getScheduler().cancelTask(this.timeoutTaskId);
            this.timeoutTaskId = -1;
        }
        if (this.scoreboardTaskId != -1) {
            Bukkit.getScheduler().cancelTask(this.scoreboardTaskId);
            this.scoreboardTaskId = -1;
        }
        if (this.shutdownTaskId != -1) {
            Bukkit.getScheduler().cancelTask(this.shutdownTaskId);
            this.shutdownTaskId = -1;
        }
    }

    private static TeamColor colorOf(String configured) {
        String key = configured.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (TeamColor color : DEFAULT_COLORS) {
            if (color.key().equals(key)) {
                return color;
            }
        }
        return DEFAULT_COLORS.getFirst();
    }

    private record ConfiguredTeam(String key, String displayName, String color, List<Location> spawns) {
    }

    private record RuntimeTeam(String name, ConfiguredTeam configuredTeam, Team scoreboardTeam) {
    }

    private record TeamColor(String key, String displayName, String chatColor) {
    }

    private record TntBlockKey(UUID worldId, int x, int y, int z) {
        private static TntBlockKey of(Block block) {
            return new TntBlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }

        private static TntBlockKey of(Location location) {
            return new TntBlockKey(location.getWorld().getUID(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
    }
}
