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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import xyz.pyxismc.tournament.common.message.JsonCodec;
import xyz.pyxismc.tournament.common.message.MatchReadyMessage;
import xyz.pyxismc.tournament.common.message.MatchResultMessage;
import xyz.pyxismc.tournament.common.message.MatchStartMessage;
import xyz.pyxismc.tournament.common.message.MessageChannels;
import xyz.pyxismc.tournament.common.redis.TournamentRedis;
import xyz.pyxismc.tournament.common.result.MatchResult;
import xyz.pyxismc.tournament.paper.message.MiniMessageUtil;

/**
 * Manages the complete lifecycle of a tournament match.
 *
 * Responsibilities:
 * - Match initialization
 * - Player preparation
 * - Combat/team protection
 * - TNT handling
 * - Projectile explosions
 * - Death/elimination handling
 * - Respawning
 * - Match timeout
 * - Match result publication
 * - Lobby transfer
 *
 * Scoreboard rendering itself is delegated to ScoreboardHandler.
 */
public final class MatchManager implements Listener {

    private static final String ARENA_TIMEOUT_KEY = "arena.timeout-seconds";
    private static final String ARENA_WORLD_KEY = "arena.world";
    private static final String ARENA_TEAMS_KEY = "arena.teams";
    private static final String ARENA_SPAWNS_KEY = "arena.spawns";

    private static final String EXPLOSION_SECTION_KEY = "arena.explosion";
    private static final String EXPLOSION_POWER_KEY = "power";
    private static final String EXPLOSION_FIRE_KEY = "fire";
    private static final String EXPLOSION_BLOCKS_KEY = "break-blocks";

    private static final String FINISH_DELAY_KEY =
            "finish.shutdown-delay-seconds";

    private static final String FINISH_LOBBY_KEY =
            "finish.lobby-server";

    private static final String BUNGEE_CHANNEL = "BungeeCord";

    /**
     * Default team configuration.
     */
    private static final List<TeamColor> DEFAULT_COLORS = List.of(
            new TeamColor("aqua", "Aqua", "#55FFFF"),
            new TeamColor("rose_pastel", "Rose Pastel", "#FF55FF"),
            new TeamColor("lime", "Lime", "#55FF55")
    );

    private final JavaPlugin plugin;
    private final TournamentRedis redis;
    private final JsonCodec codec;

    private final ScoreboardHandler scoreboardHandler;

    private final Map<TntBlockKey, Location> pendingTntSnaps =
            new HashMap<>();

    private final Map<UUID, Location> pendingSpawns =
            new HashMap<>();

    private volatile MatchSession session;

    private Map<UUID, RuntimeTeam> runtimeTeams =
            Map.of();

    private String tournamentName = "Tournament";

    private int timeoutTaskId = -1;
    private int shutdownTaskId = -1;

    public MatchManager(
            JavaPlugin plugin,
            TournamentRedis redis,
            JsonCodec codec
    ) {
        this.plugin = plugin;
        this.redis = redis;
        this.codec = codec;
        this.scoreboardHandler = new ScoreboardHandler(plugin);
    }

    /**
     * Applies a full lobby team snapshot to the scoreboard so team changes
     * made on Velocity are reflected in the lobby. Must run on the main
     * thread.
     */
    public void applyLobbyTeamSync(
            xyz.pyxismc.tournament.common.message.LobbyTeamSyncMessage message
    ) {
        this.scoreboardHandler.syncLobbyTeams(message);
    }

    /**
     * Starts a new match.
     */
    public synchronized void startMatch(MatchStartMessage message) {

        if (message == null) {
            this.plugin.getLogger().warning(
                    "Received null match start message."
            );
            return;
        }

        if (this.session != null) {
            this.plugin.getLogger().warning(
                    "A match is already running, ignoring match "
                            + message.matchId()
            );
            return;
        }

        /*
         * Build player -> team mapping.
         */
        Map<UUID, UUID> playerToTeam = new LinkedHashMap<>();

        for (UUID teamId : message.teamIds()) {

            List<UUID> players = message.playersByTeam()
                    .getOrDefault(teamId, List.of());

            for (UUID playerId : players) {
                playerToTeam.put(playerId, teamId);
            }
        }

        /*
         * Create match session.
         */
        MatchSession newSession = new MatchSession(
                message.matchId(),
                message.serverId(),
                playerToTeam,
                Instant.now()
        );

        this.session = newSession;
        this.tournamentName = message.tournamentName();

        MiniMessageUtil.setServerName(message.serverId());
        MiniMessageUtil.setTournamentName(
                message.tournamentName()
        );

        /*
         * Resolve arena.
         */
        World world = resolveWorld();

        applyArenaRules(world);

        List<ConfiguredTeam> configuredTeams =
                resolveTeamSlots(world);

        /*
         * Build runtime teams BEFORE passing them to the scoreboard.
         */
        setupScoreboardTeams(
                message,
                configuredTeams
        );

        /*
         * Prepare player spawn locations.
         */
        this.pendingSpawns.clear();

        for (
                int teamIndex = 0;
                teamIndex < message.teamIds().size();
                teamIndex++
        ) {

            UUID teamId = message.teamIds().get(teamIndex);

            ConfiguredTeam configuredTeam =
                    configuredTeams.get(
                            Math.min(
                                    teamIndex,
                                    configuredTeams.size() - 1
                            )
                    );

            List<Location> spawns =
                    configuredTeam.spawns();

            List<UUID> players =
                    message.playersByTeam()
                            .getOrDefault(teamId, List.of());

            if (spawns.isEmpty()) {
                continue;
            }

            for (
                    int playerIndex = 0;
                    playerIndex < players.size();
                    playerIndex++
            ) {

                UUID playerId = players.get(playerIndex);

                Location spawn =
                        spawns.get(
                                Math.min(
                                        playerIndex,
                                        spawns.size() - 1
                                )
                        );

                this.pendingSpawns.put(
                        playerId,
                        spawn.clone()
                );
            }
        }

        /*
         * Finalize players already connected.
         */
        for (Player player : Bukkit.getOnlinePlayers()) {

            if (!playerToTeam.containsKey(
                    player.getUniqueId()
            )) {
                continue;
            }

            finalizePlayer(player);
        }

        /*
         * Schedule match timeout.
         */
        cancelTimeoutTask();

        long timeoutTicks =
                Math.max(
                        1,
                        this.plugin.getConfig().getInt(
                                ARENA_TIMEOUT_KEY,
                                300
                        )
                ) * 20L;

        this.timeoutTaskId =
                Bukkit.getScheduler()
                        .runTaskLater(
                                this.plugin,
                                this::onTimeout,
                                timeoutTicks
                        )
                        .getTaskId();

        this.plugin.getLogger().info(
                "Match "
                        + message.matchId()
                        + " started on "
                        + message.serverId()
        );

        /*
         * Notify the orchestrator that this server is ready.
         */
        this.redis.publish(
                MessageChannels.MATCH_READY,
                this.codec.toJson(
                        new MatchReadyMessage(
                                message.matchId(),
                                message.serverId()
                        )
                )
        );
    }

    /**
     * Handles players joining the server.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {

        Player player = event.getPlayer();

        this.scoreboardHandler.applyScoreboardToPlayer(player);

        MatchSession current = this.session;

        if (current == null) {
            return;
        }

        UUID playerId = player.getUniqueId();

        if (!current.isMatchPlayer(playerId)) {
            return;
        }

        if (current.isEliminated(playerId)) {
            return;
        }

        finalizePlayer(player);
    }

    /**
     * Handles PvP damage and prevents friendly fire.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageByEntityEvent event) {

        MatchSession current = this.session;

        if (current == null) {
            return;
        }

        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        UUID victimId = victim.getUniqueId();

        if (!current.isMatchPlayer(victimId)) {
            return;
        }

        UUID attackerId =
                attackerId(event.getDamager());

        if (attackerId != null) {

            UUID victimTeam =
                    current.teamOf(victimId);

            UUID attackerTeam =
                    current.teamOf(attackerId);

            /*
             * Friendly fire protection.
             */
            if (
                    attackerTeam != null
                            && attackerTeam.equals(victimTeam)
            ) {
                event.setCancelled(true);
                return;
            }

            current.recordAttacker(
                    victimId,
                    attackerId
            );
        }

        if (!event.isCancelled()) {
            current.recordDamage(
                    victimId,
                    event.getFinalDamage()
            );
        }
    }

    /**
     * Handles player elimination.
     */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {

        MatchSession current = this.session;

        if (current == null) {
            return;
        }

        Player victim = event.getEntity();

        UUID victimId = victim.getUniqueId();

        if (
                !current.isMatchPlayer(victimId)
                        || !current.isAlive(victimId)
        ) {
            return;
        }

        event.setDeathMessage(null);

        UUID killerId =
                victim.getKiller() == null
                        ? null
                        : victim.getKiller().getUniqueId();

        current.onPlayerDeath(
                victimId,
                killerId
        );

        Bukkit.getScheduler().runTask(
                this.plugin,
                () -> {

                    if (victim.isOnline()) {
                        victim.setGameMode(
                                GameMode.SPECTATOR
                        );
                    }
                }
        );

        victim.sendMessage(
                MiniMessageUtil.error(
                        "You are out of the match."
                )
        );

        if (current.isOver()) {
            endMatch();
        }
    }

    /**
     * Handles players leaving during a match.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {

        MatchSession current = this.session;

        if (current == null) {
            return;
        }

        Player player = event.getPlayer();

        UUID playerId = player.getUniqueId();

        if (!current.isMatchPlayer(playerId)) {
            return;
        }

        current.onPlayerQuit(playerId);

        if (current.isOver()) {
            endMatch();
        }
    }

    /**
     * Captures TNT priming locations.
     */
    @EventHandler
    public void onTntPrime(TNTPrimeEvent event) {

        if (
                this.session == null
                        || event.isCancelled()
        ) {
            return;
        }

        Block block = event.getBlock();

        TntBlockKey key =
                TntBlockKey.of(block);

        this.pendingTntSnaps.put(
                key,
                centerOf(block)
        );

        Bukkit.getScheduler().runTaskLater(
                this.plugin,
                () -> this.pendingTntSnaps.remove(key),
                2L
        );
    }

    /**
     * Snaps primed TNT to the center of the source block.
     */
    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {

        if (
                this.session == null
                        || !(event.getEntity()
                        instanceof TNTPrimed tnt)
        ) {
            return;
        }

        Location target =
                snapLocation(tnt.getLocation());

        tnt.teleport(target);
        tnt.setVelocity(
                new Vector(0.0, 0.0, 0.0)
        );
    }

    /**
     * Returns placed TNT to the player's inventory.
     */
    @EventHandler(ignoreCancelled = true)
    public void onTntPlace(BlockPlaceEvent event) {

        if (
                this.session == null
                        || event.getBlockPlaced().getType()
                        != Material.TNT
        ) {
            return;
        }

        Player player = event.getPlayer();

        if (!this.session.isMatchPlayer(
                player.getUniqueId()
        )) {
            return;
        }

        Bukkit.getScheduler().runTask(
                this.plugin,
                () -> player.getInventory().addItem(
                        new ItemStack(Material.TNT, 1)
                )
        );
    }

    /**
     * Returns arrows after shooting a bow.
     */
    @EventHandler
    public void onShootBow(EntityShootBowEvent event) {

        if (
                this.session == null
                        || !(event.getEntity()
                        instanceof Player shooter)
        ) {
            return;
        }

        if (
                this.session.isMatchPlayer(
                        shooter.getUniqueId()
                )
        ) {
            shooter.getInventory().addItem(
                    new ItemStack(Material.ARROW, 1)
            );
        }
    }

    /**
     * Creates an explosion when an arrow hits something.
     */
    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {

        if (this.session == null) {
            return;
        }

        Projectile projectile =
                event.getEntity();

        if (
                !(projectile instanceof Arrow)
                        || !(projectile.getShooter()
                        instanceof Player shooter)
        ) {
            return;
        }

        if (!this.session.isMatchPlayer(
                shooter.getUniqueId()
        )) {
            return;
        }

        Location hit;

        if (event.getHitBlock() != null) {

            hit = event.getHitBlock()
                    .getLocation()
                    .add(
                            0.5,
                            0.5,
                            0.5
                    );

        } else if (event.getHitEntity() != null) {

            hit = event.getHitEntity()
                    .getLocation();

        } else {

            hit = projectile.getLocation();
        }

        var explosion =
                this.plugin.getConfig()
                        .getConfigurationSection(
                                EXPLOSION_SECTION_KEY
                        );

        float power =
                (float) (
                        explosion == null
                                ? 3.0
                                : explosion.getDouble(
                                EXPLOSION_POWER_KEY,
                                3.0
                        )
                );

        boolean fire =
                explosion == null
                        || explosion.getBoolean(
                        EXPLOSION_FIRE_KEY,
                        false
                );

        boolean breakBlocks =
                explosion == null
                        || explosion.getBoolean(
                        EXPLOSION_BLOCKS_KEY,
                        false
                );

        hit.getWorld().createExplosion(
                hit,
                power,
                fire,
                breakBlocks
        );
    }

    /**
     * Handles respawn after death.
     */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {

        Player player = event.getPlayer();

        this.scoreboardHandler.applyScoreboardToPlayer(
                player
        );

        MatchSession current = this.session;

        if (current == null) {
            return;
        }

        UUID playerId =
                player.getUniqueId();

        if (!current.isMatchPlayer(playerId)) {
            return;
        }

        RuntimeTeam team =
                this.runtimeTeams.get(
                        current.teamOf(playerId)
                );

        Location respawn;

        if (
                team == null
                        || team.configuredTeam()
                        .spawns()
                        .isEmpty()
        ) {

            World world =
                    event.getRespawnLocation()
                            .getWorld();

            if (world == null) {
                return;
            }

            respawn =
                    world.getSpawnLocation();

        } else {

            respawn =
                    team.configuredTeam()
                            .spawns()
                            .getFirst()
                            .clone();
        }

        event.setRespawnLocation(respawn);

        /*
         * Eliminated players become spectators.
         */
        if (!current.isAlive(playerId)) {

            Bukkit.getScheduler().runTask(
                    this.plugin,
                    () -> {

                        if (player.isOnline()) {
                            player.setGameMode(
                                    GameMode.SPECTATOR
                            );
                        }
                    }
            );
        }
    }

    /**
     * Prepares a player for the match.
     */
    private void finalizePlayer(Player player) {

        MatchSession current = this.session;

        if (current == null) {
            return;
        }

        UUID playerId =
                player.getUniqueId();

        if (!current.isMatchPlayer(playerId)) {
            return;
        }

        if (current.isEliminated(playerId)) {
            return;
        }

        Location spawn =
                this.pendingSpawns.getOrDefault(
                        playerId,
                        player.getLocation()
                );

        RuntimeTeam team =
                this.runtimeTeams.get(
                        current.teamOf(playerId)
                );

        player.setGameMode(
                GameMode.SURVIVAL
        );

        player.teleport(spawn.clone());

        giveKit(player);

        this.scoreboardHandler.applyScoreboardToPlayer(
                player
        );

        if (team != null) {

            String color =
                    team.configuredTeam().color();

            MiniMessageUtil.send(
                    player,
                    "<"
                            + color
                            + ">Match "
                            + this.tournamentName
                            + " started. Last team standing wins!"
            );
        }
    }

    /**
     * Cancels the active match.
     */
    public synchronized void cancelSession() {

        if (this.session == null) {
            return;
        }

        cancelTasks();

        this.session = null;

        this.pendingSpawns.clear();
        this.pendingTntSnaps.clear();

        this.runtimeTeams = Map.of();

        this.scoreboardHandler.clearMatchSession();

        MiniMessageUtil.setServerName(
                "Unknown"
        );

        MiniMessageUtil.setTournamentName(
                "Tournament"
        );

        this.plugin.getLogger().info(
                "Match session cancelled"
        );
    }

    /**
     * Handles match timeout.
     */
    private void onTimeout() {

        MatchSession current = this.session;

        if (current == null) {
            return;
        }

        this.plugin.getLogger().info(
                "Match "
                        + current.matchId()
                        + " timed out, deciding winner"
        );

        current.finishByTimeout();

        endMatch();
    }

    /**
     * Ends the match and publishes its result.
     */
    private synchronized void endMatch() {

        MatchSession current = this.session;

        if (current == null) {
            return;
        }

        /*
         * Prevent timeout from firing again.
         */
        cancelTimeoutTask();

        MatchSession finished =
                current;

        MatchResult result =
                finished.buildResult(
                        finished.matchId()
                );

        this.redis.publish(
                MessageChannels.MATCH_RESULT,
                this.codec.toJson(
                        new MatchResultMessage(
                                finished.matchId(),
                                finished.serverId(),
                                result
                        )
                )
        );

        String winner =
                winnerName(finished);

        this.plugin.getLogger().info(
                "Match "
                        + finished.matchId()
                        + " finished, winner "
                        + winner
        );

        Bukkit.broadcast(
                MiniMessageUtil.deserialize(
                        MiniMessageUtil.primary(
                                "Match finished. Winner team: "
                        ) + winner
                )
        );

        /*
         * Keep the match session alive while the finished scoreboard
         * is displayed.
         */
        this.scoreboardHandler.updateFinishedScoreboard(
                finished,
                winner
        );

        /*
         * Do NOT immediately clear the session.
         * The scoreboard still needs its final data.
         */
        Bukkit.getScheduler().runTaskLater(
                this.plugin,
                () -> {

                    this.scoreboardHandler
                            .clearMatchSession();

                    this.runtimeTeams = Map.of();

                    this.session = null;

                    this.pendingSpawns.clear();
                    this.pendingTntSnaps.clear();

                    MiniMessageUtil.setServerName(
                            "Unknown"
                    );

                    MiniMessageUtil.setTournamentName(
                            "Tournament"
                    );

                    scheduleLobbyReturnAndShutdown();

                },
                100L
        );
    }

    /**
     * Resolves or creates the arena world.
     */
    private World resolveWorld() {

        String worldName =
                this.plugin.getConfig()
                        .getString(
                                ARENA_WORLD_KEY,
                                "world"
                        );

        World world =
                Bukkit.getWorld(worldName);

        if (world != null) {
            return world;
        }

        this.plugin.getLogger().warning(
                "Arena world '"
                        + worldName
                        + "' not found, generating an empty arena world"
        );

        World created =
                Bukkit.createWorld(
                        new WorldCreator(worldName)
                                .generator(
                                        new VoidChunkGenerator()
                                )
                );

        if (created == null) {
            return Bukkit.getWorlds().getFirst();
        }

        List<ConfiguredTeam> teams =
                resolveTeamSlots(created);

        for (ConfiguredTeam team : teams) {

            for (Location spawn : team.spawns()) {
                createPlatform(
                        created,
                        spawn
                );
            }
        }

        if (
                !teams.isEmpty()
                        && !teams.getFirst()
                        .spawns()
                        .isEmpty()
        ) {

            Location first =
                    teams.getFirst()
                            .spawns()
                            .getFirst();

            created.setSpawnLocation(
                    first.getBlockX(),
                    first.getBlockY(),
                    first.getBlockZ()
            );
        }

        return created;
    }

    private static void createPlatform(
            World world,
            Location spawn
    ) {

        int x =
                spawn.getBlockX();

        int y =
                spawn.getBlockY() - 1;

        int z =
                spawn.getBlockZ();

        for (int dx = -2; dx <= 2; dx++) {

            for (int dz = -2; dz <= 2; dz++) {

                world.getBlockAt(
                        x + dx,
                        y,
                        z + dz
                ).setType(
                        Material.OBSIDIAN
                );
            }
        }
    }

    private static final class VoidChunkGenerator
            extends ChunkGenerator {
    }

    /**
     * Resolves configured team slots.
     */
    private List<ConfiguredTeam> resolveTeamSlots(
            World world
    ) {

        List<ConfiguredTeam> teams =
                new ArrayList<>();

        List<Map<?, ?>> configured =
                this.plugin.getConfig()
                        .getMapList(
                                ARENA_TEAMS_KEY
                        );

        for (
                int index = 0;
                index < configured.size();
                index++
        ) {

            Map<?, ?> raw =
                    configured.get(index);

            TeamColor color =
                    colorOf(
                            text(
                                    raw,
                                    "color",
                                    DEFAULT_COLORS
                                            .get(
                                                    index
                                                            % DEFAULT_COLORS
                                                            .size()
                                            )
                                            .key()
                            )
                    );

            String displayName =
                    text(
                            raw,
                            "display-name",
                            color.displayName()
                    );

            List<Location> spawns =
                    parseSpawns(
                            world,
                            raw.get("spawns")
                    );

            if (spawns.isEmpty()) {
                spawns = List.of(
                        world.getSpawnLocation()
                );
            }

            teams.add(
                    new ConfiguredTeam(
                            color.key(),
                            displayName,
                            color.chatColor(),
                            spawns
                    )
            );
        }

        if (!teams.isEmpty()) {
            return teams;
        }

        List<Location> legacySpawns =
                resolveLegacySpawns(world);

        int count =
                Math.max(
                        DEFAULT_COLORS.size(),
                        legacySpawns.size()
                );

        for (int i = 0; i < count; i++) {

            TeamColor color =
                    DEFAULT_COLORS.get(
                            i % DEFAULT_COLORS.size()
                    );

            Location spawn =
                    legacySpawns.get(
                            Math.min(
                                    i,
                                    legacySpawns.size() - 1
                            )
                    );

            teams.add(
                    new ConfiguredTeam(
                            color.key(),
                            color.displayName(),
                            color.chatColor(),
                            List.of(spawn)
                    )
            );
        }

        return teams;
    }

    private List<Location> resolveLegacySpawns(
            World world
    ) {

        List<Location> spawns =
                new ArrayList<>();

        for (
                Map<?, ?> raw :
                this.plugin.getConfig()
                        .getMapList(
                                ARENA_SPAWNS_KEY
                        )
        ) {

            spawns.add(
                    location(world, raw)
            );
        }

        return spawns.isEmpty()
                ? List.of(world.getSpawnLocation())
                : spawns;
    }

    private static List<Location> parseSpawns(
            World world,
            Object rawSpawns
    ) {

        if (!(rawSpawns instanceof List<?> list)) {
            return List.of();
        }

        List<Location> spawns =
                new ArrayList<>();

        for (Object entry : list) {

            if (entry instanceof Map<?, ?> spawn) {
                spawns.add(
                        location(world, spawn)
                );
            }
        }

        return spawns;
    }

    private static Location location(
            World world,
            Map<?, ?> map
    ) {

        double x =
                number(map, "x", 0.0);

        double y =
                number(map, "y", 64.0);

        double z =
                number(map, "z", 0.0);

        float yaw =
                (float) number(
                        map,
                        "yaw",
                        0.0
                );

        float pitch =
                (float) number(
                        map,
                        "pitch",
                        0.0
                );

        return new Location(
                world,
                x,
                y,
                z,
                yaw,
                pitch
        );
    }

    private static double number(
            Map<?, ?> map,
            String key,
            double fallback
    ) {

        Object value =
                map.get(key);

        return value instanceof Number number
                ? number.doubleValue()
                : fallback;
    }

    private static String text(
            Map<?, ?> map,
            String key,
            String fallback
    ) {

        Object value =
                map.get(key);

        return value == null
                || value.toString().isBlank()
                ? fallback
                : value.toString();
    }

    /**
     * Applies match-specific gamerules.
     */
    private void applyArenaRules(World world) {

        world.setGameRule(
                GameRule.FALL_DAMAGE,
                false
        );

        world.setGameRule(
                GameRule.DO_MOB_SPAWNING,
                false
        );

        world.setGameRule(
                GameRule.DO_DAYLIGHT_CYCLE,
                false
        );

        world.setGameRule(
                GameRule.DO_WEATHER_CYCLE,
                false
        );

        world.setGameRule(
                GameRule.DO_FIRE_TICK,
                false
        );

        world.setGameRule(
                GameRule.KEEP_INVENTORY,
                true
        );

        world.setGameRule(
                GameRule.ANNOUNCE_ADVANCEMENTS,
                false
        );

        world.setGameRule(
                GameRule.WATER_SOURCE_CONVERSION,
                false
        );
    }

    /**
     * Creates the Bukkit teams used by the match.
     *
     * These are separate from the persistent sidebar line teams.
     */
    private void setupScoreboardTeams(
            MatchStartMessage message,
            List<ConfiguredTeam> configuredTeams
    ) {

        Map<UUID, RuntimeTeam> teams =
                new LinkedHashMap<>();

        List<ScoreboardHandler.MatchTeamDef> matchTeamDefs =
                new ArrayList<>();

        for (
                int index = 0;
                index < message.teamIds().size();
                index++
        ) {

            UUID teamId =
                    message.teamIds().get(index);

            ConfiguredTeam configuredTeam =
                    configuredTeams.get(
                            Math.min(
                                    index,
                                    configuredTeams.size() - 1
                            )
                    );

            String teamName =
                    message.teamNames()
                            .getOrDefault(
                                    teamId,
                                    configuredTeam.displayName()
                            );

            List<String> memberNames =
                    new ArrayList<>();

            for (
                    UUID playerId :
                    message.playersByTeam()
                            .getOrDefault(
                                    teamId,
                                    List.of()
                            )
            ) {

                Player player =
                        Bukkit.getPlayer(playerId);

                if (player != null) {
                    memberNames.add(
                            player.getName()
                    );
                }
            }

            matchTeamDefs.add(
                    new ScoreboardHandler.MatchTeamDef(
                            teamName,
                            toChatColor(
                                    configuredTeam.color()
                            ),
                            memberNames
                    )
            );

            teams.put(
                    teamId,
                    new RuntimeTeam(
                            teamName,
                            configuredTeam
                    )
            );
        }

        this.runtimeTeams = teams;

        /*
         * Register the match name teams on every player's scoreboard.
         */
        this.scoreboardHandler.setMatchTeams(
                matchTeamDefs
        );

        /*
         * IMPORTANT:
         * The scoreboard handler receives the fully constructed
         * RuntimeTeam map only after every team exists.
         */
        this.scoreboardHandler.setMatchSession(
                this.session,
                this.runtimeTeams
        );
    }

    /**
     * Gives the configured kit to a player.
     */
    private void giveKit(Player player) {

        PlayerInventory inventory =
                player.getInventory();

        inventory.clear();

        var armor =
                this.plugin.getConfig()
                        .getConfigurationSection(
                                "kit.armor"
                        );

        if (armor != null) {

            inventory.setHelmet(
                    armorItem(armor, "helmet")
            );

            inventory.setChestplate(
                    armorItem(armor, "chestplate")
            );

            inventory.setLeggings(
                    armorItem(armor, "leggings")
            );

            inventory.setBoots(
                    armorItem(armor, "boots")
            );
        }

        for (
                Map<?, ?> raw :
                this.plugin.getConfig()
                        .getMapList(
                                "kit.inventory"
                        )
        ) {

            int slot =
                    (int) number(
                            raw,
                            "slot",
                            -1
                    );

            if (
                    slot < 0
                            || slot >= inventory.getSize()
            ) {
                continue;
            }

            String material =
                    text(
                            raw,
                            "material",
                            "AIR"
                    );

            int amount =
                    Math.max(
                            1,
                            Math.min(
                                    64,
                                    (int) number(
                                            raw,
                                            "amount",
                                            1
                                    )
                            )
                    );

            inventory.setItem(
                    slot,
                    item(
                            material,
                            amount,
                            raw.get("enchantments")
                    )
            );
        }

        player.updateInventory();
    }

    private ItemStack armorItem(
            ConfigurationSection armor,
            String key
    ) {

        /*
         * A piece may be a plain material name (no enchantments) or a map
         * with "material" and optional "enchantments".
         */
        if (armor.isConfigurationSection(key)) {

            ConfigurationSection piece =
                    armor.getConfigurationSection(key);

            return item(
                    piece.getString(
                            "material",
                            null
                    ),
                    1,
                    piece.get("enchantments")
            );
        }

        return item(
                armor.getString(key),
                1,
                null
        );
    }

    private ItemStack item(
            String materialName,
            int amount,
            Object enchantments
    ) {

        if (materialName == null) {
            return null;
        }

        Material material =
                Material.matchMaterial(
                        materialName
                );

        if (
                material == null
                        || material == Material.AIR
        ) {
            return null;
        }

        ItemStack stack =
                new ItemStack(
                        material,
                        amount
                );

        if (
                enchantments instanceof Map<?, ?> map
                        && !map.isEmpty()
        ) {

            ItemMeta meta =
                    stack.getItemMeta();

            if (meta != null) {

                for (
                        Map.Entry<?, ?> entry :
                        map.entrySet()
                ) {

                    Enchantment enchantment =
                            Enchantment.getByKey(
                                    NamespacedKey.minecraft(
                                            String.valueOf(
                                                    entry.getKey()
                                            ).toLowerCase(
                                                    Locale.ROOT
                                            )
                                    )
                            );

                    if (
                            enchantment != null
                                    && entry.getValue()
                                    instanceof Number level
                    ) {

                        meta.addEnchant(
                                enchantment,
                                Math.max(
                                        1,
                                        level.intValue()
                                ),
                                true
                        );
                    }
                }

                stack.setItemMeta(meta);
            }
        }

        return stack;
    }

    /**
     * Snaps TNT to the center of the block that primed it.
     */
    private Location snapLocation(
            Location spawned
    ) {

        TntBlockKey key =
                TntBlockKey.of(spawned);

        Location target =
                this.pendingTntSnaps.remove(key);

        if (target != null) {
            return target;
        }

        return new Location(
                spawned.getWorld(),
                spawned.getBlockX() + 0.5,
                spawned.getBlockY(),
                spawned.getBlockZ() + 0.5,
                spawned.getYaw(),
                spawned.getPitch()
        );
    }

    private static Location centerOf(
            Block block
    ) {

        return new Location(
                block.getWorld(),
                block.getX() + 0.5,
                block.getY(),
                block.getZ() + 0.5
        );
    }

    /**
     * Resolves the attacking player from direct or projectile damage.
     */
    private static UUID attackerId(
            Entity damager
    ) {

        if (damager instanceof Player player) {
            return player.getUniqueId();
        }

        if (
                damager instanceof Projectile projectile
                        && projectile.getShooter()
                        instanceof Player player
        ) {
            return player.getUniqueId();
        }

        return null;
    }

    /**
     * Schedules the lobby transfer and server shutdown.
     */
    private void scheduleLobbyReturnAndShutdown() {

        int delaySeconds =
                this.plugin.getConfig()
                        .getInt(
                                FINISH_DELAY_KEY,
                                5
                        );

        cancelShutdownTask();

        this.shutdownTaskId =
                Bukkit.getScheduler()
                        .runTaskLater(
                                this.plugin,
                                () -> {

                                    String lobby =
                                            this.plugin.getConfig()
                                                    .getString(
                                                            FINISH_LOBBY_KEY,
                                                            "lobby"
                                                    );

                                    for (
                                            Player player :
                                            Bukkit.getOnlinePlayers()
                                    ) {
                                        sendToLobby(
                                                player,
                                                lobby
                                        );
                                    }

                                    Bukkit.getScheduler()
                                            .runTaskLater(
                                                    this.plugin,
                                                    Bukkit::shutdown,
                                                    20L
                                            );
                                },
                                Math.max(
                                        0,
                                        delaySeconds
                                ) * 20L
                        )
                        .getTaskId();
    }

    private void sendToLobby(
            Player player,
            String lobby
    ) {

        try (
                ByteArrayOutputStream bytes =
                        new ByteArrayOutputStream();

                DataOutputStream out =
                        new DataOutputStream(bytes)
        ) {

            out.writeUTF("Connect");
            out.writeUTF(lobby);

            player.sendPluginMessage(
                    this.plugin,
                    BUNGEE_CHANNEL,
                    bytes.toByteArray()
            );

        } catch (IOException e) {

            this.plugin.getLogger().warning(
                    "Could not send "
                            + player.getName()
                            + " to lobby: "
                            + e.getMessage()
            );
        }
    }

    /**
     * Cancels lifecycle tasks without touching the scoreboard task.
     */
    private void cancelTasks() {
        cancelTimeoutTask();
        cancelShutdownTask();
    }

    private void cancelTimeoutTask() {

        if (this.timeoutTaskId != -1) {

            Bukkit.getScheduler()
                    .cancelTask(
                            this.timeoutTaskId
                    );

            this.timeoutTaskId = -1;
        }
    }

    private void cancelShutdownTask() {

        if (this.shutdownTaskId != -1) {

            Bukkit.getScheduler()
                    .cancelTask(
                            this.shutdownTaskId
                    );

            this.shutdownTaskId = -1;
        }
    }

    private static TeamColor colorOf(
            String configured
    ) {

        String key =
                configured
                        .toLowerCase(Locale.ROOT)
                        .replace('-', '_')
                        .replace(' ', '_');

        for (TeamColor color :
                DEFAULT_COLORS) {

            if (color.key().equals(key)) {
                return color;
            }
        }

        return DEFAULT_COLORS.getFirst();
    }

    private static ChatColor toChatColor(
            String hexColor
    ) {

        return switch (
                hexColor.toLowerCase(Locale.ROOT)
                ) {

            case "#55ffff" ->
                    ChatColor.AQUA;

            case "#ff55ff" ->
                    ChatColor.LIGHT_PURPLE;

            case "#55ff55" ->
                    ChatColor.GREEN;

            default ->
                    ChatColor.WHITE;
        };
    }

    /**
     * Returns the display name of the winning team.
     */
    private String winnerName(
            MatchSession finished
    ) {

        return finished.winnerTeamId()
                .map(teamId -> {

                    RuntimeTeam team =
                            this.runtimeTeams.get(teamId);

                    if (team == null) {
                        return teamId.toString();
                    }

                    String color =
                            team.configuredTeam().color();

                    return "<"
                            + color
                            + ">"
                            + team.name()
                            + "</"
                            + color
                            + ">";
                })
                .orElse("none");
    }

    private record TeamColor(
            String key,
            String displayName,
            String chatColor
    ) {
    }

    private record TntBlockKey(
            UUID worldId,
            int x,
            int y,
            int z
    ) {

        private static TntBlockKey of(
                Block block
        ) {

            return new TntBlockKey(
                    block.getWorld().getUID(),
                    block.getX(),
                    block.getY(),
                    block.getZ()
            );
        }

        private static TntBlockKey of(
                Location location
        ) {

            World world =
                    location.getWorld();

            if (world == null) {
                throw new IllegalArgumentException(
                        "Location has no world"
                );
            }

            return new TntBlockKey(
                    world.getUID(),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            );
        }
    }
}
