package xyz.pyxismc.tournament.paper.match;

import java.util.List;
import org.bukkit.Location;

record ConfiguredTeam(String key, String displayName, String color, List<Location> spawns) {
}
