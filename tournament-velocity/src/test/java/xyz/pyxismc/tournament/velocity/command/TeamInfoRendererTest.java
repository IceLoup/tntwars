package xyz.pyxismc.tournament.velocity.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import xyz.pyxismc.tournament.common.enums.TeamRole;
import xyz.pyxismc.tournament.common.model.Team;
import xyz.pyxismc.tournament.common.model.TeamPlayer;

class TeamInfoRendererTest {

    private static Team team(boolean locked) {
        UUID captainId = UUID.randomUUID();
        return new Team(
                UUID.randomUUID(),
                "TNT Crew",
                captainId,
                List.of(
                        new TeamPlayer(captainId, "Captain", TeamRole.CAPTAIN),
                        new TeamPlayer(UUID.randomUUID(), "Member1", TeamRole.MEMBER),
                        new TeamPlayer(UUID.randomUUID(), "Member2", TeamRole.MEMBER)),
                locked);
    }

    @Test
    void renderContainsNameCaptainAndMembers() {
        String rendered = TeamInfoRenderer.render(team(false));

        assertTrue(rendered.contains("TNT Crew"));
        assertTrue(rendered.contains("Captain"));
        assertTrue(rendered.contains("Member1"));
        assertTrue(rendered.contains("Member2"));
        assertFalse(rendered.contains("locked"));
    }

    @Test
    void renderMarksLockedTeams() {
        String rendered = TeamInfoRenderer.render(team(true));

        assertTrue(rendered.contains("locked"));
    }
}