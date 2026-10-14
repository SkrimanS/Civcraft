package com.avrgaming.civcraft.modern.trade;

import java.io.File;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

public final class TradeShipCatalog {
    private final Map<Integer,TradeShipProgress.Level> levels;
    public TradeShipCatalog(File structures) {
        Map<Integer,TradeShipProgress.Level> loaded = new TreeMap<>();
        for (var raw : YamlConfiguration.loadConfiguration(structures).getMapList("tradeship_levels")) {
            int id = ((Number)raw.get("level")).intValue();
            var level = new TradeShipProgress.Level(id,((Number)raw.get("upgradeTrade")).intValue(),
                    ((Number)raw.get("maxTrade")).intValue(),((Number)raw.get("culture")).doubleValue());
            if (loaded.putIfAbsent(id,level)!=null) throw new IllegalArgumentException("Duplicate trade ship level: "+id);
        }
        if (loaded.isEmpty() || loaded.size()>10) throw new IllegalArgumentException("Missing or excessive trade ship levels");
        for (int i=1;i<=loaded.size();i++)
            if(!loaded.containsKey(i)) throw new IllegalArgumentException("Missing trade ship level: "+i);
        levels = Map.copyOf(loaded);
    }
    public Map<Integer,TradeShipProgress.Level> levels() {return levels;}
}
