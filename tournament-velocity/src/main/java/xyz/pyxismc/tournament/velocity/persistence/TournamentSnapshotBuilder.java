package xyz.pyxismc.tournament.velocity.persistence;

import java.util.UUID;

import xyz.pyxismc.tournament.common.dto.TournamentSnapshot;
import xyz.pyxismc.tournament.common.model.Tournament;
import xyz.pyxismc.tournament.velocity.round.RoundManager;
import xyz.pyxismc.tournament.velocity.team.TeamManager;

/**
 * Assembles a {@link TournamentSnapshot} from the in-memory managers. The
 * teams list reflects the teams at save time (captain names may be stale).
 */
public final class TournamentSnapshotBuilder {

    private final TeamManager teamManager;
    private final RoundManager roundManager;

    public TournamentSnapshotBuilder(TeamManager teamManager, RoundManager roundManager) {
        this.teamManager = teamManager;
        this.roundManager = roundManager;
    }

    public TournamentSnapshot build(Tournament tournament) {
        UUID championTeamId = this.roundManager.getChampionTeamId().orElse(null);
        return new TournamentSnapshot(
                tournament,
                this.teamManager.getTeams(),
                this.roundManager.getRounds(),
                this.roundManager.getMatches(),
                this.roundManager.getResults(),
                championTeamId);
    }
}