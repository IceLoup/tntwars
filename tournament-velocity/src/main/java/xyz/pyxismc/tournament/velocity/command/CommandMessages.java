package xyz.pyxismc.tournament.velocity.command;

import com.velocitypowered.api.command.CommandSource;

/** Shared MiniMessage output helpers for every command. */
public final class CommandMessages {

    private CommandMessages() {
    }

    /** Red error text. */
    public static void error(CommandSource source, String message) {
        source.sendRichMessage("<red>Error: " + message);
    }

    /** Green success text. */
    public static void success(CommandSource source, String message) {
        source.sendRichMessage("<green>" + message);
    }

    /** Yellow informational text. */
    public static void info(CommandSource source, String message) {
        source.sendRichMessage("<yellow>" + message);
    }

    /** Gray debug text. */
    public static void debug(CommandSource source, String message) {
        source.sendRichMessage("<gray>" + message);
    }
}
