package xyz.pyxismc.tournament.velocity.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import xyz.pyxismc.tournament.common.dto.TournamentSnapshot;
import xyz.pyxismc.tournament.common.dto.TournamentSummary;

/**
 * Storage of the tournament history (PostgreSQL implementation in
 * {@link PostgresTournamentRepository}). All methods block on the caller's
 * thread and must be called outside the proxy event loop.
 */
public interface TournamentRepository {

    /** Persists the full tournament tree (single transaction). */
    void saveTournament(TournamentSnapshot snapshot);

    /** Most recent tournaments, newest first. */
    List<TournamentSummary> listTournaments(int limit);

    /** Full tournament details, if stored. */
    Optional<TournamentSnapshot> getTournament(UUID tournamentId);
}