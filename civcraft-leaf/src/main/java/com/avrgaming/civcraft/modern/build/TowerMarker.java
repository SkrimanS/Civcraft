package com.avrgaming.civcraft.modern.build;
import java.util.List;
public final class TowerMarker {
    private TowerMarker() {}
    public static boolean firing(List<String> lines){return !lines.isEmpty()&&lines.getFirst().trim().equalsIgnoreCase("/towerfire");}
}
