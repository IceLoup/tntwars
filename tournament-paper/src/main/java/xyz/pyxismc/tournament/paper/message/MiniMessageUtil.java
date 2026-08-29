package xyz.pyxismc.tournament.paper.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

public final class MiniMessageUtil {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    public static final String PRIMARY = "#55FFFF";
    public static final String SECONDARY = "#FF55FF";
    public static final String ERROR = "#FF5555";
    public static final String SUCCESS = "#55FF55";
    public static final String WARNING = "#FFAA00";
    public static final String INFO = "#55FFFF";
    public static final String MUTED = "#888888";

    private static String serverName = "Unknown";
    private static String tournamentName = "Tournament";

    private MiniMessageUtil() {
    }

    public static void setServerName(String name) {
        serverName = name;
    }

    public static void setTournamentName(String name) {
        tournamentName = name;
    }

    public static String getServerName() {
        return serverName;
    }

    public static String getTournamentName() {
        return tournamentName;
    }

    public static Component deserialize(String input, TagResolver... resolvers) {
        return MINI_MESSAGE.deserialize(input, addPlaceholders(resolvers));
    }

    public static void send(Player player, String input, TagResolver... resolvers) {
        player.sendMessage(deserialize(input, resolvers));
    }

    public static void sendError(Player player, String message) {
        send(player, "<" + ERROR + ">Error: </" + ERROR + ">" + message);
    }

    public static void sendSuccess(Player player, String message) {
        send(player, "<" + SUCCESS + ">" + message);
    }

    public static void sendInfo(Player player, String message) {
        send(player, "<" + INFO + ">" + message);
    }

    public static void sendWarning(Player player, String message) {
        send(player, "<" + WARNING + ">" + message);
    }

    public static String primary(String text) {
        return "<" + PRIMARY + ">" + text + "</" + PRIMARY + ">";
    }

    public static String secondary(String text) {
        return "<" + SECONDARY + ">" + text + "</" + SECONDARY + ">";
    }

    public static String error(String text) {
        return "<" + ERROR + ">" + text + "</" + ERROR + ">";
    }

    public static String success(String text) {
        return "<" + SUCCESS + ">" + text + "</" + SUCCESS + ">";
    }

    public static String warning(String text) {
        return "<" + WARNING + ">" + text + "</" + WARNING + ">";
    }

    public static String info(String text) {
        return "<" + INFO + ">" + text + "</" + INFO + ">";
    }

    public static String muted(String text) {
        return "<" + MUTED + ">" + text + "</" + MUTED + ">";
    }

    public static String gradient(String text) {
        return "<gradient:" + PRIMARY + ":" + SECONDARY + ">" + text + "</gradient>";
    }

    private static TagResolver[] addPlaceholders(TagResolver... custom) {
        TagResolver[] combined = new TagResolver[custom.length + 3];
        System.arraycopy(custom, 0, combined, 0, custom.length);
        combined[custom.length] = Placeholder.unparsed("server_name", serverName);
        combined[custom.length + 1] = Placeholder.unparsed("tournament_name", tournamentName);
        combined[custom.length + 2] = Placeholder.unparsed("online_players", String.valueOf(Bukkit.getOnlinePlayers().size()));
        return combined;
    }

    public static TagResolver serverPlaceholder() {
        return Placeholder.unparsed("server_name", serverName);
    }

    public static TagResolver tournamentPlaceholder() {
        return Placeholder.unparsed("tournament_name", tournamentName);
    }

    public static TagResolver playerCountPlaceholder() {
        return Placeholder.unparsed("online_players", String.valueOf(Bukkit.getOnlinePlayers().size()));
    }
}