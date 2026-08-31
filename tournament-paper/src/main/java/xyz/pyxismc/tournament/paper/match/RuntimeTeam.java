package xyz.pyxismc.tournament.paper.match;

import org.bukkit.scoreboard.Team;

record RuntimeTeam(String name, ConfiguredTeam configuredTeam, Team scoreboardTeam) {
}
