package com.avrgaming.civcraft.modern.wall;

import java.io.File;
import org.bukkit.configuration.file.YamlConfiguration;

/** Wall and fortified-wall settings are independent sections of the original war.yml. */
public record WallCatalog(Profile ordinary,Profile fortified) {
    public record Profile(String type,int height,int maximumHeight,double segmentCost,double maximumSegment,int recursionLimit,int hitpoints) {
        public Profile {
            if(height<1 || !Double.isFinite(segmentCost) || segmentCost<0 || !Double.isFinite(maximumSegment)
                    || maximumSegment<0 || recursionLimit<0 || hitpoints<1)
                throw new IllegalArgumentException("Неверные настройки стены "+type);
        }
        public boolean fortified(){return type.equals("ti_fortifiedwall");}
    }
    public Profile profile(String type) {
        return switch(type) {
            case "ti_wall" -> ordinary;
            case "ti_fortifiedwall" -> fortified;
            default -> throw new IllegalArgumentException("Неизвестный вид стены: "+type);
        };
    }
    public static WallCatalog load(File warFile,File structuresFile) {
        var war=YamlConfiguration.loadConfiguration(warFile);
        var structures=YamlConfiguration.loadConfiguration(structuresFile);
        return new WallCatalog(read(war,structures,"wall","ti_wall"),read(war,structures,"fortified_wall","ti_fortifiedwall"));
    }
    private static Profile read(YamlConfiguration war,YamlConfiguration structures,String section,String type) {
        var definition=structures.getMapList("structures").stream().filter(row->type.equals(row.get("id"))).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Не настроена постройка "+type));
        for(String key:new String[]{"height","maximum_height","cost_per_segment","max_segment","recursion_limit"})
            if(!war.isSet(section+"."+key))throw new IllegalArgumentException("Не настроен параметр стены "+section+"."+key);
        return new Profile(type,war.getInt(section+".height"),war.getInt(section+".maximum_height"),war.getDouble(section+".cost_per_segment"),
                war.getDouble(section+".max_segment"),war.getInt(section+".recursion_limit"),Integer.parseInt(definition.get("max_hitpoints").toString()));
    }
}
