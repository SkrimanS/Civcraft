package com.avrgaming.civcraft.modern.build;
import java.util.*;
public final class ShipBatteryPlan {
    private ShipBatteryPlan() {}
    public static List<LegacyBlock> groups(LegacyDefTemplate template) {
        var groups=new TreeMap<Integer,LegacyBlock>();
        for(var block:template.blocks())if(TowerMarker.firing(block.signLines())) {
            Integer id=null;for(var line:block.signLines())if(line.trim().toLowerCase(Locale.ROOT).startsWith("id:"))try{id=Integer.parseInt(line.trim().substring(3).trim());}catch(NumberFormatException failure){throw new IllegalArgumentException("Invalid ship battery id",failure);}
            if(id==null)throw new IllegalArgumentException("Missing ship battery id");groups.putIfAbsent(id,block);
        }
        return List.copyOf(groups.values());
    }
}
