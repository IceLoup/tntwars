package xyz.pyxismc.tournament.velocity.event;

import xyz.pyxismc.tournament.common.model.Tournament;

/** Fired when a tournament is cancelled by an administrator. */
public record TournamentCancelledEvent(Tournament tournament) {
}