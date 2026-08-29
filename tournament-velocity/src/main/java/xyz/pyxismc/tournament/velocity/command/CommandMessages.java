package xyz.pyxismc.tournament.velocity.command;

import com.velocitypowered.api.command.CommandSource;

/** Shared MiniMessage output helpers for every command. */
public final class CommandMessages {

    private CommandMessages() {
    }

    private static final String PRIMARY = "#8693AB";
    private static final String SECONDARY = "#BDD4E7";
    private static final String ERROR = "#FF5555";
    private static final String SUCCESS = "#55FF55";
    private static final String WARNING = "#FFAA00";
    private static final String MUTED = "#888888";

    /** Red error text. */
    public static void error(CommandSource source, String message) {
        source.sendRichMessage("<" + ERROR + ">Error: </" + ERROR + ">" + message);
    }

    /** Green success text. */
    public static void success(CommandSource source, String message) {
        source.sendRichMessage("<" + SUCCESS + ">" + message);
    }

    /** Primary info text. */
    public static void info(CommandSource source, String message) {
        source.sendRichMessage("<" + PRIMARY + ">" + message);
    }

    /** Secondary informational text. */
    public static void secondary(CommandSource source, String message) {
        source.sendRichMessage("<" + SECONDARY + ">" + message);
    }

    /** Muted debug text. */
    public static void debug(CommandSource source, String message) {
        source.sendRichMessage("<" + MUTED + ">" + message);
    }

    /** Warning text. */
    public static void warning(CommandSource source, String message) {
        source.sendRichMessage("<" + WARNING + ">" + message);
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

    public static String muted(String text) {
        return "<" + MUTED + ">" + text + "</" + MUTED + ">";
    }
}
