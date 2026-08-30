package xyz.pyxismc.tournament.paper;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import xyz.pyxismc.tournament.common.message.JsonCodec;
import xyz.pyxismc.tournament.common.message.MatchStartMessage;
import xyz.pyxismc.tournament.common.message.MessageChannels;
import xyz.pyxismc.tournament.common.redis.JedisTournamentRedis;
import xyz.pyxismc.tournament.common.redis.TournamentRedis;
import xyz.pyxismc.tournament.paper.match.MatchManager;
import xyz.pyxismc.tournament.paper.placeholder.TournamentPlaceholderExpansion;

/**
 * Match execution plugin. Connects to Redis, pops its own match-instruction
 * key and runs the match: arena rules, kits, eliminations and the final
 * result report back to Velocity.
 */
public final class TournamentPaperPlugin extends JavaPlugin {

    private static final String SERVER_ID_KEY = "server-id";

    private TournamentRedis redis;
    private MatchManager matchManager;
    private Thread matchWorker;
    private volatile boolean running;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        String serverId = firstNonBlank(System.getenv("TOURNAMENT_SERVER_ID"),
                getConfig().getString(SERVER_ID_KEY, "game"));
        String host = firstNonBlank(System.getenv("REDIS_HOST"), getConfig().getString("redis.host", "localhost"));
        int port = Integer.parseInt(firstNonBlank(System.getenv("REDIS_PORT"),
                Integer.toString(getConfig().getInt("redis.port", 6379))));
        String password = firstNonBlank(System.getenv("REDIS_PASSWORD"), getConfig().getString("redis.password", ""));
        try {
            this.redis = new JedisTournamentRedis(host, port, password);
            this.matchManager = new MatchManager(this, this.redis, new JsonCodec());
            getServer().getPluginManager().registerEvents(this.matchManager, this);
            this.running = true;
            String queue = MessageChannels.matchQueue(serverId);
            this.matchWorker = new Thread(() -> pollMatchInstructions(queue), "tournament-paper-match");
            this.matchWorker.setDaemon(true);
            this.matchWorker.start();
            getLogger().info("Connected to Redis at " + host + ":" + port
                    + ", waiting for match instructions on '" + serverId + "'");
            // Signal that this server is ready to receive players
            this.redis.publish(MessageChannels.MATCH_READY_FOR_PLAYERS, serverId);
        } catch (Exception e) {
            getLogger().warning("Redis unavailable, match execution disabled: " + e.getMessage());
        }

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new TournamentPlaceholderExpansion().register();
            getLogger().info("PlaceholderAPI expansion registered (placeholders: %tournament_server_name%, %tournament_tournament_name%, %tournament_online_players%)");
        } else {
            getLogger().warning("PlaceholderAPI not found, placeholders will not be available globally");
        }
    }

    @Override
    public void onDisable() {
        this.running = false;
        if (this.matchWorker != null) {
            this.matchWorker.interrupt();
            this.matchWorker = null;
        }
        if (this.matchManager != null) {
            this.matchManager.cancelSession();
        }
        if (this.redis != null) {
            this.redis.close();
            this.redis = null;
        }
    }

    /**
     * Blocking pop on the match-instruction list: the message stays in Redis
     * until this server (which boots after Velocity provisioned it) is ready,
     * no race with the pub/sub subscription deadline.
     */
    private void pollMatchInstructions(String queue) {
        while (this.running) {
            String payload;
            try {
                payload = this.redis.pop(queue, 5);
            } catch (RuntimeException e) {
                if (this.running) {
                    getLogger().warning("Redis popped no match instruction, retrying: " + e.getMessage());
                }
                continue;
            }
            if (payload == null) {
                continue;
            }
            String received = payload;
            Bukkit.getScheduler().runTask(this, () -> onMatchStart(received));
        }
    }

    private void onMatchStart(String payload) {
        try {
            MatchStartMessage message = new JsonCodec().fromJson(payload, MatchStartMessage.class);
            Bukkit.getScheduler().runTask(this, () -> this.matchManager.startMatch(message));
        } catch (RuntimeException e) {
            getLogger().warning("Malformed match-start message ignored: " + e.getMessage());
        }
    }

    /** First non-blank value, so containers can override config with env vars. */
    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}