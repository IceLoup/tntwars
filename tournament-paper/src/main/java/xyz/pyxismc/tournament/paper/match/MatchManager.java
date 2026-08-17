package xyz.pyxismc.tournament.paper.match;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import xyz.pyxismc.tournament.common.message.JsonCodec;
import xyz.pyxismc.tournament.common.message.MatchReadyMessage;
import xyz.pyxismc.tournament.common.message.MatchResultMessage;
import xyz.pyxismc.tournament.common.message.MatchStartMessage;
import xyz.pyxismc.tournament.common.message.MessageChannels;
import xyz.pyxismc.tournament.common.redis.TournamentRedis;
import xyz.pyxismc.tournament.common.result.MatchResult;

/**
 * Match runtime on one temporary server: spawns the teams, applies the arena
 * rules and the TNT-wars kit, tracks deaths and eliminations and reports the
 * final {@link MatchResultMessage} to Velocity. One session at a time: a
 * match server hosts exactly one match.
 */
public final class MatchManager implements Listener {

    private static final String ARENA_TIMEOUT_KEY = "arena.timeout-seconds";
    private static final String ARENA_WORLD_KEY = "arena.world";
    private static final String ARENA_SPAWNS_KEY = "arena.spawns";
    private static final String EXPLOSION_SECTION_KEY = "arena.explosion";
    private static final String EXPLOSION_POWER_KEY = "power";
    private static final String EXPLOSION_FIRE_KEY = "fire";
    private static final String EXPLOSION_BLOCKS_KEY = "break-blocks";

    private final JavaPlugin plugin;
    private final TournamentRedis redis;
    private final JsonCodec codec;

    private volatile MatchSession session;
    private int timeoutTaskId = -1;

    public MatchManager(JavaPlugin plugin, TournamentRedis redis, JsonCodec codec) {
        this.plugin = plugin;
        this.redis = redis;
        this.codec = codec;
    }

    /** Starts a match from the instructions published by Velocity. */
    public synchronized void startMatch(MatchStartMessage message) {
        if (this.session != null) {
            this.plugin.getLogger().warning("A match is already running, ignoring match " + message.matchId());
            return;
        }
        Map<UUID, UUID> playerToTeam = new HashMap<>();
        for (Map.Entry<UUID, List<UUID>> entry : message.playersByTeam().entrySet()) {
            for (UUID playerId : entry.getValue()) {
                playerToTeam.put(playerId, entry.getKey());
            }
        }
        MatchSession newSession = new MatchSession(
                message.matchId(), message.serverId(), playerToTeam, Instant.now());
        this.session = newSession;

        World world = resolveWorld();
        applyArenaRules(world);
        List<Location> spawns = resolveSpawns(world);
        for (int i = 0; i < message.teamIds().size(); i++) {
            UUID teamId = message.teamIds().get(i);
            Location spawn = spawns.get(Math.min(i, spawns.size() - 1));
            for (UUID playerId : message.playersByTeam().getOrDefault(teamId, List.of())) {
                Player player = Bukkit.getPlayer(playerId);
                if (player == null) {
                    newSession.onPlayerQuit(playerId);
                    continue;
                }
                player.teleport(spawn);
                giveKit(player);
                player.sendMessage("Match " + message.tournamentName() + " started. Last team standing wins!");
            }
        }

        this.timeoutTaskId = Bukkit.getScheduler().runTaskLater(this.plugin, this::onTimeout,
                (long) this.plugin.getConfig().getInt(ARENA_TIMEOUT_KEY, 300) * 20L).getTaskId();

        this.plugin.getLogger().info("Match " + message.matchId() + " started on " + message.serverId());
        this.redis.publish(MessageChannels.MATCH_READY, this.codec.toJson(
                new MatchReadyMessage(message.matchId(), message.serverId())));
    }

    /** Cancels the current session (server shutdown / tournament cancel). */
    public synchronized void cancelSession() {
        if (this.session == null) {
            return;
        }
        this.session = null;
        Bukkit.getScheduler().cancelTask(this.timeoutTaskId);
        this.plugin.getLogger().info("Match session cancelled");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (this.session == null) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!this.session.isMatchPlayer(victim.getUniqueId())) {
            return;
        }
        if (event.getDamager() instanceof Player attacker) {
            UUID attackerId = attacker.getUniqueId();
            if (this.session.isMatchPlayer(attackerId)
                    && this.session.teamOf(attackerId).equals(this.session.teamOf(victim.getUniqueId()))) {
                event.setCancelled(true);
                return;
            }
            this.session.recordAttacker(victim.getUniqueId(), attackerId);
        }
        if (event.isCancelled()) {
            return;
        }
        this.session.recordDamage(victim.getUniqueId(), event.getFinalDamage());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (this.session == null) {
            return;
        }
        Player victim = event.getEntity();
        if (!this.session.isMatchPlayer(victim.getUniqueId())) {
            return;
        }
        event.setDeathMessage(null);
        UUID killerId = victim.getKiller() == null ? null : victim.getKiller().getUniqueId();
        this.session.onPlayerDeath(victim.getUniqueId(), killerId);
        victim.sendMessage("You are out of the match.");
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
        if (this.session.isOver()) {
            endMatch();
        }
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
            event.setRespawnLocation(event.getRespawnLocation().getWorld().getSpawnLocation());
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
        MatchResult result = finished.buildResult(finished.matchId());
        this.redis.publish(MessageChannels.MATCH_RESULT, this.codec.toJson(
                new MatchResultMessage(finished.matchId(), finished.serverId(), result)));
        String winner = finished.winnerTeamId().map(UUID::toString).orElse("none");
        this.plugin.getLogger().info("Match " + finished.matchId() + " finished, winner " + winner);
        Bukkit.broadcastMessage("Match finished. Winner team: " + winner);
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
        List<Location> spawns = resolveSpawns(created);
        for (Location spawn : spawns) {
            createPlatform(created, spawn);
        }
        if (!spawns.isEmpty()) {
            Location first = spawns.get(0);
            created.setSpawnLocation(first.getBlockX(), first.getBlockY(), first.getBlockZ());
        }
        return created;
    }

    /** Small obsidian pad under a spawn so players do not fall into the void. */
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

    /** Generates a completely empty world: template containers ship no terrain. */
    private static final class VoidChunkGenerator extends ChunkGenerator {
    }

    private List<Location> resolveSpawns(World world) {
        List<Location> spawns = new ArrayList<>();
        for (Map<?, ?> raw : this.plugin.getConfig().getMapList(ARENA_SPAWNS_KEY)) {
            double x = number(raw, "x", 0.0);
            double y = number(raw, "y", 64.0);
            double z = number(raw, "z", 0.0);
            float yaw = (float) number(raw, "yaw", 0.0);
            spawns.add(new Location(world, x, y, z, yaw, 0.0F));
        }
        return spawns.isEmpty() ? List.of(world.getSpawnLocation()) : spawns;
    }

    private static double number(Map<?, ?> map, String key, double fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private void applyArenaRules(World world) {
        world.setGameRule(GameRule.FALL_DAMAGE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
    }

    private void giveKit(Player player) {
        player.getInventory().clear();
        player.getInventory().setItem(0, new ItemStack(Material.BOW));
        player.getInventory().setItem(1, new ItemStack(Material.ARROW, 1));
    }
}