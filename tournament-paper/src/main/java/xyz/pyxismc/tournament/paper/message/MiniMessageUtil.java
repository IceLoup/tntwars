package xyz.pyxismc.tournament.paper.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class MiniMessageUtil {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    public static final String PRIMARY = "#55FFFF";
    public static final String SECONDARY = "#FF55FF";
    public static final String ERROR = "#FF5555";
    public static final String SUCCESS = "#55FF55";
    public static final String WARNING = "#FFAA00";
    public static final String INFO = "#55FFFF";
    public static final String MUTED = "#888888";

    private static volatile String serverName = "Unknown";
    private static volatile String tournamentName = "Tournament";

    private MiniMessageUtil() {
    }

    public static void setServerName(String name) {
        serverName = name == null || name.isBlank() ? "Unknown" : name;
    }

    public static void setTournamentName(String name) {
        tournamentName = name == null || name.isBlank() ? "Tournament" : name;
    }

    public static String getServerName() {
        return serverName;
    }

    public static String getTournamentName() {
        return tournamentName;
    }

    /**
     * Deserializes a MiniMessage string and automatically adds the
     * global placeholders.
     */
    public static Component deserialize(String input, TagResolver... resolvers) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }

        return MINI_MESSAGE.deserialize(input, addPlaceholders(resolvers));
    }

    public static void send(Player player, String input, TagResolver... resolvers) {
        if (player == null) {
            return;
        }

        player.sendMessage(deserialize(input, resolvers));
    }

    public static void sendError(Player player, String message) {
        send(player, color(ERROR, "Error: ") + message);
    }

    public static void sendSuccess(Player player, String message) {
        send(player, color(SUCCESS, message));
    }

    public static void sendInfo(Player player, String message) {
        send(player, color(INFO, message));
    }

    public static void sendWarning(Player player, String message) {
        send(player, color(WARNING, message));
    }

    public static String primary(String text) {
        return color(PRIMARY, text);
    }

    public static String secondary(String text) {
        return color(SECONDARY, text);
    }

    public static String error(String text) {
        return color(ERROR, text);
    }

    public static String success(String text) {
        return color(SUCCESS, text);
    }

    public static String warning(String text) {
        return color(WARNING, text);
    }

    public static String info(String text) {
        return color(INFO, text);
    }

    public static String muted(String text) {
        return color(MUTED, text);
    }

    public static String gradient(String text) {
        return "<gradient:" + PRIMARY + ":" + SECONDARY + ">"
                + text
                + "</gradient>";
    }

    private static String color(String color, String text) {
        return "<" + color + ">" + (text == null ? "" : text);
    }

    private static TagResolver[] addPlaceholders(TagResolver... custom) {
        int customLength = custom == null ? 0 : custom.length;

        TagResolver[] combined = new TagResolver[customLength + 3];

        if (customLength > 0) {
            System.arraycopy(custom, 0, combined, 0, customLength);
        }

        combined[customLength] =
                Placeholder.unparsed("server_name", serverName);

        combined[customLength + 1] =
                Placeholder.unparsed("tournament_name", tournamentName);

        combined[customLength + 2] =
                Placeholder.unparsed(
                        "online_players",
                        String.valueOf(Bukkit.getOnlinePlayers().size())
                );

        return combined;
    }

    public static TagResolver serverPlaceholder() {
        return Placeholder.unparsed("server_name", serverName);
    }

    public static TagResolver tournamentPlaceholder() {
        return Placeholder.unparsed("tournament_name", tournamentName);
    }

    public static TagResolver playerCountPlaceholder() {
        return Placeholder.unparsed(
                "online_players",
                String.valueOf(Bukkit.getOnlinePlayers().size())
        );
    }
}

