/**
 * Paper plugin running on the match servers.
 * <p>
 * Responsibilities (built phase by phase): receiving the match configuration,
 * running the game mode, collecting statistics, detecting match end, sending
 * the result to Velocity and returning players to the lobby.
 * Paper never decides the overall tournament progression.
 */
package xyz.pyxismc.tournament.paper;
