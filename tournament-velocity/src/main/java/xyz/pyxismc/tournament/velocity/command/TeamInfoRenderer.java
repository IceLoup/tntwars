package xyz.pyxismc.tournament.velocity.command;

import java.util.List;

import xyz.pyxismc.tournament.common.enums.TeamRole;
import xyz.pyxismc.tournament.common.model.Team;
import xyz.pyxismc.tournament.common.model.TeamPlayer;

/** Renders a team as MiniMessage markup for the {@code /team info} command. */
public final class TeamInfoRenderer {

    private static final String PRIMARY = "#55FFFF";
    private static final String SECONDARY = "#FF55FF";
    private static final String MUTED = "#888888";
    private static final String ACCENT = "#55FF55";

    private TeamInfoRenderer() {
    }

    public static String render(Team team) {
        String captain = team.players().stream()
                .filter(player -> player.role() == TeamRole.CAPTAIN)
                .map(TeamPlayer::username)
                .findFirst()
                .orElse(team.captainId().toString());

        List<String> members = team.players().stream()
                .filter(player -> player.role() == TeamRole.MEMBER)
                .map(TeamPlayer::username)
                .toList();

        StringBuilder text = new StringBuilder();
        text.append("<").append(PRIMARY).append(">Team </").append(PRIMARY).append(">")
                .append("<").append(ACCENT).append(">").append(team.name()).append("</").append(ACCENT).append(">");
        if (team.locked()) {
            text.append(" <#FF5555>(locked)</#FF5555>");
        }
        text.append("\n<").append(MUTED).append(">Captain: </").append(MUTED).append(">")
                .append("<").append(SECONDARY).append(">").append(captain).append("</").append(SECONDARY).append(">");
        text.append("\n<").append(MUTED).append(">Members: </").append(MUTED).append(">");
        if (members.isEmpty()) {
            text.append("<").append(MUTED).append(">None</").append(MUTED).append(">");
        } else {
            text.append(String.join(", ", members));
        }
        return text.toString();
    }
}
