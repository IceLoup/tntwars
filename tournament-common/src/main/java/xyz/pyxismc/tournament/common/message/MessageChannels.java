package xyz.pyxismc.tournament.common.message;

/**
 * Redis keys and channel names of the Velocity <-> Paper protocol.
 * A Paper match server only subscribes to its own match channel.
 */
public final class MessageChannels {

    /** Redis list holding pending {@link ProvisionRequest} payloads. */
    public static final String PROVISION_QUEUE = "tournament:provision:queue";

    /** Pub/Sub channel on which Paper match servers acknowledge readiness. */
    public static final String MATCH_READY = "tournament:match-ready";

    /** Pub/Sub channel on which Paper match servers report final results. */
    public static final String MATCH_RESULT = "tournament:match-result";

    private MessageChannels() {
    }

    /** Channel on which the match instructions are published for a server. */
    public static String matchChannel(String serverId) {
        return "tournament:match:" + serverId;
    }
}