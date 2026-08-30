package xyz.pyxismc.tournament.common.message;

/**
 * Redis keys and channel names of the Velocity <-> Paper protocol.
 * A Paper match server only consumes its own match instruction key.
 */
public final class MessageChannels {

    /** Redis list holding pending {@link ProvisionRequest} payloads. */
    public static final String PROVISION_QUEUE = "tournament:provision:queue";

    /** Pub/Sub channel on which Paper match servers acknowledge readiness. */
    public static final String MATCH_READY = "tournament:match-ready";

    /** Pub/Sub channel on which Paper match servers signal they are ready to receive players. */
    public static final String MATCH_READY_FOR_PLAYERS = "tournament:match-ready-for-players";

    /**
     * Returns the pub/sub channel used by a Paper match server to signal it is ready to receive players.
     * @param serverId the server ID
     * @return the channel name
     */
    public static String matchReadyForPlayersChannel(String serverId) {
        return MATCH_READY_FOR_PLAYERS + ":" + serverId;
    }

    /** Pub/Sub channel on which Paper match servers report final results. */
    public static final String MATCH_RESULT = "tournament:match-result";

    private MessageChannels() {
    }

    /**
     * Redis list on which the match instructions are queued for a server.
     * A list (not pub/sub) so a message survives until the freshly booted
     * match server pops it, even if that happens after the publish.
     */
    public static String matchQueue(String serverId) {
        return "tournament:match:" + serverId;
    }
}