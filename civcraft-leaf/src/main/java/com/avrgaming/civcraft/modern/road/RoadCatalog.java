package com.avrgaming.civcraft.modern.road;

import java.io.File;
import org.bukkit.configuration.file.YamlConfiguration;

public record RoadCatalog(double playerSpeed,double horseSpeed,int raidHours,int maximum,double segmentCost,int health,double demolishRefund) {
    public RoadCatalog{
        if(!Double.isFinite(playerSpeed)||playerSpeed<=0 || !Double.isFinite(horseSpeed)||horseSpeed<=0 || raidHours<0 || raidHours>24 || maximum<1 || !Double.isFinite(segmentCost)||segmentCost<0 || health<1 || !Double.isFinite(demolishRefund)||demolishRefund<0)throw new IllegalArgumentException("Неверные настройки дороги");
    }
    public static RoadCatalog load(File file){
        var y=YamlConfiguration.loadConfiguration(file);var definition=y.getMapList("structures").stream().filter(row->"ti_road".equals(row.get("id"))).findFirst().orElseThrow(()->new IllegalArgumentException("Не настроена постройка ti_road"));
        return new RoadCatalog(y.getDouble("road.player_speed",1.5),y.getDouble("road.horse_speed",1.1),y.getInt("road.raid_length",2),y.getInt("road.max_segment",50),y.getDouble("road.cost_per_segment",300),Integer.parseInt(definition.get("max_hitpoints").toString()),0);
    }
}
