package xyz.pyxismc.tournament.paper.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import xyz.pyxismc.tournament.paper.message.MiniMessageUtil;

public final class TournamentPlaceholderExpansion extends PlaceholderExpansion {

    @Override
    public @NotNull String getIdentifier() {
        return "tournament";
    }

    @Override
    public @NotNull String getAuthor() {
        return "PyxisMC";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        return switch (params) {
            case "server_name" -> MiniMessageUtil.getServerName();
            case "tournament_name" -> MiniMessageUtil.getTournamentName();
            case "online_players" -> String.valueOf(org.bukkit.Bukkit.getOnlinePlayers().size());
            default -> null;
        };
    }
}