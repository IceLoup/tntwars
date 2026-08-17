/**
 * Domain events fired by the tournament managers. These events implement the
 * Velocity {@code Event} interface and can be listened to by other plugins.
 * <p>
 * The events live here (not in tournament-common) because common cannot depend
 * on the Velocity API, and these events are Velocity-internal.
 */
package xyz.pyxismc.tournament.velocity.event;
