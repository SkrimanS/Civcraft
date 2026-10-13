package com.avrgaming.civcraft.modern.town;

import java.io.File;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Original hourly food costs, count thresholds and cottage/temple output. */
public final class ConsumptionCatalog {
    public record Level(int number, int count, Map<Integer,Integer> food, double output) {
        public Level {
            if(number<1 || count<1 || !Double.isFinite(output) || output<0)
                throw new IllegalArgumentException("Invalid consumption level");
            food=Map.copyOf(food);
            if(food.isEmpty() || food.entrySet().stream().anyMatch(e->e.getKey()<1 || e.getValue()<1))
                throw new IllegalArgumentException("Invalid consumption food");
        }
    }
    private final Map<String,Map<Integer,Level>> buildings;
    public ConsumptionCatalog(File structures) {
        var yaml=YamlConfiguration.loadConfiguration(structures);
        var all=new LinkedHashMap<String,Map<Integer,Level>>();
        all.put("ti_cottage",read(yaml,"cottage_levels","coins"));
        all.put("s_temple",read(yaml,"temple_levels","culture"));
        buildings=Map.copyOf(all);
    }
    public Map<Integer,Level> levels(String structure) {
        var result=buildings.get(structure);
        if(result==null)throw new IllegalArgumentException("Unsupported consuming structure: "+structure);
        return result;
    }
    private static Map<Integer,Level> read(YamlConfiguration yaml,String section,String outputKey) {
        var result=new TreeMap<Integer,Level>();
        for(var raw:yaml.getMapList(section)) {
            int number=integer(raw.get("level")),count=integer(raw.get("count"));
            if(!(raw.get("consumes") instanceof List<?> consumes) || !(raw.get(outputKey) instanceof Number output))
                throw new IllegalArgumentException("Invalid consumption configuration: "+section);
            var food=new LinkedHashMap<Integer,Integer>();
            for(var value:consumes) {
                var pair=value.toString().split(",",-1);
                if(pair.length!=2)throw new IllegalArgumentException("Invalid food requirement: "+value);
                int id=Integer.parseInt(pair[0].trim()),amount=Integer.parseInt(pair[1].trim());
                if(food.putIfAbsent(id,amount)!=null)throw new IllegalArgumentException("Duplicate food requirement: "+id);
            }
            if(result.putIfAbsent(number,new Level(number,count,food,output.doubleValue()))!=null)
                throw new IllegalArgumentException("Duplicate consumption level: "+number);
        }
        if(result.isEmpty() || result.lastKey()!=result.size() || result.firstKey()!=1)
            throw new IllegalArgumentException("Consumption levels must be consecutive from one: "+section);
        return Collections.unmodifiableMap(result);
    }
    private static int integer(Object value) {
        if(!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()!=n.intValue())
            throw new IllegalArgumentException("Invalid consumption integer: "+value);
        return n.intValue();
    }
}
