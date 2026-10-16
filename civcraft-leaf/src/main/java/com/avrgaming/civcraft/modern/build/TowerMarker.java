package com.avrgaming.civcraft.modern.build;
import java.util.List;
public final class TowerMarker {
    private TowerMarker() {}
    public static boolean firing(List<String> lines){return !lines.isEmpty()&&lines.getFirst().trim().equalsIgnoreCase("/towerfire");}
    public static boolean arrow(List<String> lines){return !lines.isEmpty()&&lines.getFirst().trim().equalsIgnoreCase("/arrowfire");}
    public static boolean cannon(List<String> lines){return !lines.isEmpty()&&lines.getFirst().trim().equalsIgnoreCase("/cannonfire");}
    public static boolean hidden(List<String> lines){return firing(lines)||arrow(lines)||cannon(lines);}
}
