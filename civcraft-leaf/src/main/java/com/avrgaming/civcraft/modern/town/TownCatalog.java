package com.avrgaming.civcraft.modern.town;

import java.io.File;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

public final class TownCatalog {
    public record Level(int number,String title,int plots,long plotCost,long upkeep,int improvements) {}
    private final Map<String,UpgradeDefinition> upgrades=new LinkedHashMap<>();
    private final Map<Integer,Level> levels=new LinkedHashMap<>();
    public TownCatalog(File file) {
        var yaml=YamlConfiguration.loadConfiguration(file);
        for(var raw:yaml.getMapList("town_levels")) {
            int level=((Number)raw.get("level")).intValue();
            levels.put(level,new Level(level,text(raw.get("title")),((Number)raw.get("plots")).intValue(),money(raw.get("plot_cost")),money(raw.get("upkeep")),((Number)raw.get("tile_improvements")).intValue()));
        }
        for(var raw:yaml.getMapList("upgrades")) {
            String[] action=text(raw.get("action")).split(",",2);
            if(action.length!=2)throw new IllegalArgumentException("Неверное действие улучшения: "+raw.get("id"));
            String id=text(raw.get("id"));
            upgrades.put(id,new UpgradeDefinition(id,text(raw.get("name")),money(raw.get("cost")),action[0].trim(),action[1].trim(),text(raw.get("require_upgrade")),text(raw.get("require_tech")),text(raw.get("require_structure")),text(raw.get("category"))));
        }
        level(1);
    }
    public Level level(int number){var level=levels.get(number);if(level==null)throw new IllegalArgumentException("Неизвестный уровень города: "+number);return level;}
    public UpgradeDefinition require(String name) {
        var found=upgrades.get(name);
        if(found==null)found=upgrades.values().stream().filter(u->u.name().equalsIgnoreCase(name)).findFirst().orElse(null);
        if(found==null)throw new IllegalArgumentException("Неизвестное улучшение: "+name);return found;
    }
    public List<UpgradeDefinition> upgrades(){return List.copyOf(upgrades.values());}
    public long nextPlotCost(int count) {
        return levels.values().stream().filter(l->count<l.plots()).min(Comparator.comparingInt(Level::plots))
                .orElseGet(()->levels.values().stream().max(Comparator.comparingInt(Level::plots)).orElseThrow()).plotCost();
    }
    private static String text(Object value){return value==null?"":value.toString().trim();}
    private static long money(Object value) {
        double amount=value==null?0:((Number)value).doubleValue();
        if(!Double.isFinite(amount) || amount<0 || amount!=Math.rint(amount) || amount>1_000_000_000_000L)throw new IllegalArgumentException("Неверное количество для города");
        return (long)amount;
    }
}
