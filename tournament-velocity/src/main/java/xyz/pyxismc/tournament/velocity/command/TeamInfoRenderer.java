package xyz.pyxismc.tournament.velocity.command;

import java.util.List;

import xyz.pyxismc.tournament.common.enums.TeamRole;
import xyz.pyxismc.tournament.common.model.Team;
import xyz.pyxismc.tournament.common.model.TeamPlayer;

/** Renders a team as MiniMessage markup for the {@code /team info} command. */
public final class TeamInfoRenderer {

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
        text.append("<yellow>Team <gold>").append(team.name());
        if (team.locked()) {
            text.append(" <red>(locked)");
        }
        text.append("\n<gray>Captain: <white>").append(captain);
        text.append("\n<gray>Members: <white>");
        if (members.isEmpty()) {
            text.append("<gray>None");
        } else {
            text.append(String.join(", ", members));
        }
        return text.toString();
    }
}
