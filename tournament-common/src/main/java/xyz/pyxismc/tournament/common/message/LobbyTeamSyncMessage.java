package xyz.pyxismc.tournament.common.message;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Full snapshot of the lobby team state, published by Velocity to Paper so
 * the sidebar scoreboard can render each player's teammates.
 *
 * <p>Contains one entry per team; each entry maps to the ordered list of
 * member player UUIDs. Players without a team are simply absent.</p>
 */
public record LobbyTeamSyncMessage(List<TeamEntry> teams) {

    public LobbyTeamSyncMessage {
        if (teams == null) {
            teams = List.of();
        } else {
            teams = List.copyOf(teams);
        }
    }

    public static LobbyTeamSyncMessage empty() {
        return new LobbyTeamSyncMessage(List.of());
    }

    /**
     * A single team: its unique id and the ordered list of member player ids.
     */
    public record TeamEntry(UUID teamId, String name, List<UUID> members) {

        public TeamEntry {
            Objects.requireNonNull(teamId, "teamId");
            if (name == null) {
                name = "";
            }
            if (members == null) {
                members = List.of();
            } else {
                members = List.copyOf(members);
            }
        }
    }
}
