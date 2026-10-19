package com.avrgaming.civcraft.modern.government;

import java.io.File;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

public final class GovernmentDefinitions {
    private final Map<String,GovernmentDefinition> entries=new LinkedHashMap<>();
    private final double maximumAnarchyHours;
    private final double notreMaximumHours,broadcastReduction,notreReduction;
    public GovernmentDefinitions(File file) {
        var yaml=YamlConfiguration.loadConfiguration(file);
        maximumAnarchyHours=yaml.getDouble("anarchy_duration",24);
        if(!Double.isFinite(maximumAnarchyHours) || maximumAnarchyHours<0)throw new IllegalArgumentException("Неверная длительность анархии");
        notreMaximumHours=yaml.getDouble("notre_dame_max_anarchy",2);
        var buffs=YamlConfiguration.loadConfiguration(new File(file.getParentFile(),"buffs.yml"));
        Map<String,Double> values=new HashMap<>();
        for(var buff:buffs.getMapList("buffs")) {
            String id=text(buff.get("id"));
            if(id.equals("buff_reduced_anarchy") || id.equals("buff_notre_dame_no_anarchy"))values.put(id,Double.parseDouble(text(buff.get("value"))));
        }
        broadcastReduction=values.getOrDefault("buff_reduced_anarchy",0.5);
        notreReduction=values.getOrDefault("buff_notre_dame_no_anarchy",0.5);
        if(!Double.isFinite(notreMaximumHours) || notreMaximumHours<0
                || !Double.isFinite(broadcastReduction) || broadcastReduction<0 || broadcastReduction>1
                || !Double.isFinite(notreReduction) || notreReduction<0 || notreReduction>1)throw new IllegalArgumentException("Неверные настройки сокращённой анархии");
        for(var raw:yaml.getMapList("governments")) {
            String id=text(raw.get("id"));
            if(id.isBlank())throw new IllegalArgumentException("Не указан ID правительства");
            var definition=new GovernmentDefinition(id,text(raw.get("displayName")),text(raw.get("require_tech")),
                    number(raw,"hammer_rate"),number(raw,"beaker_rate"),number(raw,"trade_rate"),number(raw,"cottage_rate"),
                    number(raw,"upkeep_rate"),number(raw,"growth_rate"),number(raw,"culture_rate"),number(raw,"maximum_tax_rate"));
            entries.put(id,definition);
        }
        require("gov_anarchy");require("gov_tribalism");
    }
    public GovernmentDefinition require(String name) {
        String id=normalize(name);
        var found=entries.get(id);
        if(found==null)found=entries.values().stream().filter(d->d.name().equalsIgnoreCase(name)).findFirst().orElse(null);
        if(found==null)throw new IllegalArgumentException("Неизвестное правительство: "+name);
        return found;
    }
    public List<GovernmentDefinition> list(){return List.copyOf(entries.values());}
    public double maximumAnarchyHours(){return maximumAnarchyHours;}
    public double notreMaximumHours(){return notreMaximumHours;}
    public double broadcastReduction(){return broadcastReduction;}
    public double notreReduction(){return notreReduction;}
    public static String normalize(String name){String id=name.toLowerCase(Locale.ROOT).trim();return id.startsWith("gov_")?id:"gov_"+id;}
    private static String text(Object value){return value==null?"":value.toString();}
    private static double number(Map<?,?> map,String key) {
        Object value=map.get(key);
        double number=value==null?1:Double.parseDouble(value.toString());
        if(!Double.isFinite(number) || number<0)throw new IllegalArgumentException("Неверный коэффициент правительства: "+key);
        return number;
    }
}
