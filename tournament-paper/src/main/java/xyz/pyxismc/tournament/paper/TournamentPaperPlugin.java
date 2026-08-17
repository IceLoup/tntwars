package xyz.pyxismc.tournament.paper;

import org.bukkit.plugin.java.JavaPlugin;

import xyz.pyxismc.tournament.common.message.JsonCodec;
import xyz.pyxismc.tournament.common.message.MatchStartMessage;
import xyz.pyxismc.tournament.common.message.MessageChannels;
import xyz.pyxismc.tournament.common.redis.JedisTournamentRedis;
import xyz.pyxismc.tournament.common.redis.TournamentRedis;
import xyz.pyxismc.tournament.paper.match.MatchManager;

/**
 * Match execution plugin. Connects to Redis, subscribes to its own match
 * channel and runs the match: arena rules, kits, eliminations and the final
 * result report back to Velocity.
 */
public final class TournamentPaperPlugin extends JavaPlugin {

    private static final String SERVER_ID_KEY = "server-id";

    private TournamentRedis redis;
    private MatchManager matchManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
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
            this.redis.subscribe(MessageChannels.matchChannel(serverId), this::onMatchStart);
            getLogger().info("Connected to Redis at " + host + ":" + port
                    + ", waiting for match instructions on '" + serverId + "'");
        } catch (Exception e) {
            getLogger().warning("Redis unavailable, match execution disabled: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (this.matchManager != null) {
            this.matchManager.cancelSession();
        }
        if (this.redis != null) {
            this.redis.close();
            this.redis = null;
        }
    }

    private void onMatchStart(String payload) {
        try {
            MatchStartMessage message = new JsonCodec().fromJson(payload, MatchStartMessage.class);
            this.matchManager.startMatch(message);
        } catch (RuntimeException e) {
            getLogger().warning("Malformed match-start message ignored: " + e.getMessage());
        }
    }

    /** First non-blank value, so containers can override config with env vars. */
    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}