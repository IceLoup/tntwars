package xyz.pyxismc.tournament.velocity.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xyz.pyxismc.tournament.common.enums.TeamRole;
import xyz.pyxismc.tournament.common.model.Player;
import xyz.pyxismc.tournament.common.model.Team;
import xyz.pyxismc.tournament.common.model.TeamPlayer;
import xyz.pyxismc.tournament.velocity.config.TournamentConfig;
import xyz.pyxismc.tournament.velocity.event.TeamCreatedEvent;
import xyz.pyxismc.tournament.velocity.event.TeamDisbandedEvent;
import xyz.pyxismc.tournament.velocity.testutil.FakeEventBus;

class TeamManagerTest {

    private FakeEventBus eventBus;
    private TeamManager manager;

    @BeforeEach
    void setUp() {
        this.eventBus = new FakeEventBus();
        this.manager = new TeamManager(TournamentConfig.defaults(), this.eventBus);
    }

    private static Player player(String name) {
        return new Player(UUID.randomUUID(), name);
    }

    private static Player player(UUID id, String name) {
        return new Player(id, name);
    }

    @Test
    void createTeamMakesCaptainTheFirstMember() {
        Player captain = player("Captain");

        Team team = this.manager.createTeam(captain, "TNT Crew");

        assertEquals("TNT Crew", team.name());
        assertEquals(captain.uuid(), team.captainId());
        assertEquals(List.of(new TeamPlayer(captain.uuid(), "Captain", TeamRole.CAPTAIN)), team.players());
        assertFalse(team.locked());
        assertTrue(this.manager.teamOfPlayer(captain.uuid()).isPresent());
        assertEquals(1, this.eventBus.count(TeamCreatedEvent.class));
    }

    @Test
    void createTeamRejectsPlayerAlreadyInATeam() {
        Player captain = player("Captain");
        this.manager.createTeam(captain, "TNT Crew");

        TeamException e = assertThrows(TeamException.class, () -> this.manager.createTeam(captain, "Other"));
        assertTrue(e.getMessage().contains("already in a team"));
        assertEquals(1, this.eventBus.count(TeamCreatedEvent.class));
    }

    @Test
    void createTeamRejectsBlankAndDuplicateNames() {
        Player captain = player("Captain");
        assertThrows(TeamException.class, () -> this.manager.createTeam(captain, "  "));
        this.manager.createTeam(captain, "TNT Crew");

        TeamException e = assertThrows(TeamException.class, () -> this.manager.createTeam(player("Other"), "tnt crew"));
        assertTrue(e.getMessage().contains("already exists"));
    }

    @Test
    void createTeamRejectsTooLongNames() {
        assertThrows(TeamException.class, () -> this.manager.createTeam(
                player("Captain"), "a".repeat(TeamManager.MAX_TEAM_NAME_LENGTH + 1)));
    }

    @Test
    void inviteRequiresCaptainAndFreeTarget() {
        Player captain = player("Captain");
        Team team = this.manager.createTeam(captain, "TNT Crew");
        Player member = player("Member");
        Player stranger = player("Stranger");
        this.manager.invitePlayer(captain.uuid(), member.uuid());
        this.manager.acceptInvite(member, team.id());

        assertThrows(TeamException.class, () -> this.manager.invitePlayer(member.uuid(), stranger.uuid()));
        assertThrows(TeamException.class, () -> this.manager.invitePlayer(captain.uuid(), captain.uuid()));
        assertThrows(TeamException.class, () -> this.manager.invitePlayer(captain.uuid(), member.uuid()));

        this.manager.invitePlayer(captain.uuid(), stranger.uuid());
        TeamException e = assertThrows(TeamException.class, () -> this.manager.invitePlayer(captain.uuid(), stranger.uuid()));
        assertTrue(e.getMessage().contains("pending invitation"));
    }

    @Test
    void inviteIsRejectedWhenTeamIsFull() {
        Player captain = player("Captain");
        Team team = this.manager.createTeam(captain, "TNT Crew");
        Player a = player("A");
        Player b = player("B");
        this.manager.invitePlayer(captain.uuid(), a.uuid());
        this.manager.acceptInvite(a, team.id());
        this.manager.invitePlayer(captain.uuid(), b.uuid());
        this.manager.acceptInvite(b, team.id());

        TeamException e = assertThrows(TeamException.class, () -> this.manager.invitePlayer(captain.uuid(), player("C").uuid()));
        assertTrue(e.getMessage().contains("full"));
    }

    @Test
    void acceptInviteAddsMemberAndConsumesInvite() {
        Player captain = player("Captain");
        Team team = this.manager.createTeam(captain, "TNT Crew");
        Player member = player("Member");

        TeamException missing = assertThrows(TeamException.class, () -> this.manager.acceptInvite(member, team.id()));
        assertTrue(missing.getMessage().contains("no pending invitation"));

        this.manager.invitePlayer(captain.uuid(), member.uuid());
        Team updated = this.manager.acceptInvite(member, team.id());

        assertEquals(2, updated.players().size());
        assertEquals(TeamRole.MEMBER, updated.players().get(1).role());
        assertTrue(this.manager.teamOfPlayer(member.uuid()).isPresent());

        TeamException again = assertThrows(TeamException.class, () -> this.manager.acceptInvite(member, team.id()));
        assertTrue(again.getMessage().contains("already in a team"));
    }

    @Test
    void acceptInviteIsRejectedWhenFull() {
        Player captain = player("Captain");
        Team team = this.manager.createTeam(captain, "TNT Crew");
        Player outsider = player("Outsider");
        this.manager.invitePlayer(captain.uuid(), outsider.uuid());
        Player a = player("A");
        Player b = player("B");
        this.manager.invitePlayer(captain.uuid(), a.uuid());
        this.manager.acceptInvite(a, team.id());
        this.manager.invitePlayer(captain.uuid(), b.uuid());
        this.manager.acceptInvite(b, team.id());

        TeamException e = assertThrows(TeamException.class, () -> this.manager.acceptInvite(outsider, team.id()));
        assertTrue(e.getMessage().contains("full"));
    }

    @Test
    void pendingInvitationOfTracksInvitedPlayer() {
        Player captain = player("Captain");
        Team team = this.manager.createTeam(captain, "TNT Crew");
        Player member = player("Member");

        assertTrue(this.manager.pendingInvitationOf(member.uuid()).isEmpty());
        assertEquals(0, this.manager.pendingInvitationCount());

        this.manager.invitePlayer(captain.uuid(), member.uuid());

        assertTrue(this.manager.pendingInvitationOf(member.uuid()).isPresent());
        assertEquals(team.id(), this.manager.pendingInvitationOf(member.uuid()).orElseThrow());
        assertEquals(1, this.manager.pendingInvitationCount());
    }

    @Test
    void pendingInvitationIsClearedAfterAcceptOrDisband() {
        Player captain = player("Captain");
        Team team = this.manager.createTeam(captain, "TNT Crew");
        Player member = player("Member");
        this.manager.invitePlayer(captain.uuid(), member.uuid());

        this.manager.acceptInvite(member, team.id());
        assertTrue(this.manager.pendingInvitationOf(member.uuid()).isEmpty());
        assertEquals(0, this.manager.pendingInvitationCount());

        Player other = player("Other");
        this.manager.invitePlayer(captain.uuid(), other.uuid());
        this.manager.disbandTeam(captain);
        assertTrue(this.manager.pendingInvitationOf(other.uuid()).isEmpty());
        assertEquals(0, this.manager.pendingInvitationCount());
    }

    @Test
    void memberLeavesButCaptainCannot() {
        Player captain = player("Captain");
        Team team = this.manager.createTeam(captain, "TNT Crew");
        Player member = player("Member");
        this.manager.invitePlayer(captain.uuid(), member.uuid());
        this.manager.acceptInvite(member, team.id());

        assertThrows(TeamException.class, () -> this.manager.leaveTeam(captain));
        this.manager.leaveTeam(member);

        assertTrue(this.manager.teamOfPlayer(member.uuid()).isEmpty());
        Team remaining = this.manager.getTeam(team.id()).orElseThrow();
        assertEquals(1, remaining.players().size());
        assertEquals(captain.uuid(), remaining.captainId());
    }

    @Test
    void playerCannotLeaveWhenNotInATeam() {
        TeamException e = assertThrows(TeamException.class, () -> this.manager.leaveTeam(player("Loner")));
        assertTrue(e.getMessage().contains("not in a team"));
    }

    @Test
    void disbandFreesMembersAndClearsInvites() {
        Player captain = player("Captain");
        Team team = this.manager.createTeam(captain, "TNT Crew");
        Player member = player("Member");
        Player outsider = player("Outsider");
        this.manager.invitePlayer(captain.uuid(), member.uuid());
        this.manager.acceptInvite(member, team.id());
        this.manager.invitePlayer(captain.uuid(), outsider.uuid());

        assertThrows(TeamException.class, () -> this.manager.disbandTeam(member));

        this.manager.disbandTeam(captain);

        assertTrue(this.manager.getTeam(team.id()).isEmpty());
        assertTrue(this.manager.teamOfPlayer(captain.uuid()).isEmpty());
        assertTrue(this.manager.teamOfPlayer(member.uuid()).isEmpty());
        assertEquals(1, this.eventBus.count(TeamDisbandedEvent.class));

        this.manager.createTeam(captain, "New Team");
        assertTrue(this.manager.teamOfPlayer(captain.uuid()).isPresent());
    }

    @Test
    void lockedTeamRejectsMutations() {
        Player captain = player("Captain");
        Team team = this.manager.createTeam(captain, "TNT Crew");
        Player member = player("Member");
        this.manager.invitePlayer(captain.uuid(), member.uuid());
        this.manager.acceptInvite(member, team.id());

        this.manager.lockAllTeams();
        assertTrue(this.manager.isLocked(team.id()));

        assertThrows(TeamException.class, () -> this.manager.invitePlayer(captain.uuid(), player("X").uuid()));
        assertThrows(TeamException.class, () -> this.manager.acceptInvite(player("Y"), team.id()));
        assertThrows(TeamException.class, () -> this.manager.leaveTeam(member));
        assertThrows(TeamException.class, () -> this.manager.disbandTeam(captain));
    }

    @Test
    void unlockAllTeamsReleasesLock() {
        Player captain = player("Captain");
        Team team = this.manager.createTeam(captain, "TNT Crew");
        this.manager.lockAllTeams();
        assertTrue(this.manager.isLocked(team.id()));

        this.manager.unlockAllTeams();
        assertFalse(this.manager.isLocked(team.id()));
    }
}
