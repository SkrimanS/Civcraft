package com.avrgaming.civcraft.modern.trade;

import java.io.File;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

public final class StableCatalog {
    public record Horse(int id,double speed,double jump,double health,String color,String name,boolean mule) {}
    public record Offer(int index,int group,int item,int horse,long price,String name) {}
    private final Map<Integer,Horse> horses;
    private final Map<Integer,List<Offer>> groups;
    public StableCatalog(File file){
        var config=YamlConfiguration.loadConfiguration(file);var animals=new LinkedHashMap<Integer,Horse>();
        for(var row:config.getMapList("stable_horses")){
            int id=integer(row,"id");double speed=number(row,"speed"),jump=number(row,"jump"),health=number(row,"health");
            if(id<1 || jump<0 || jump>2 || health<=0 || health>2048 || speed<-.5 || speed>1)throw new IllegalArgumentException("Неверная лошадь конюшни");
            var horse=new Horse(id,speed,jump,health,Objects.toString(row.get("variant")),Objects.toString(row.get("name")),Boolean.TRUE.equals(row.get("mule")));
            if(animals.putIfAbsent(id,horse)!=null)throw new IllegalArgumentException("Повторная лошадь конюшни");
        }
        var offers=new LinkedHashMap<Integer,List<Offer>>();
        for(var row:config.getMapList("stable_items")){
            int group=integer(row,"store_id"),item=integer(row,"item_id"),horse=integer(row,"horse_id");double cost=number(row,"cost");
            if(group<0 || group>2 || cost<0 || cost>1e12 || cost!=Math.rint(cost) || (item==0 && !animals.containsKey(horse)))throw new IllegalArgumentException("Неверное предложение конюшни");
            var entries=offers.computeIfAbsent(group,k->new ArrayList<>());String name=Objects.toString(row.get("name"));
            name=switch(name){case "Horse"->"Лошадь";case "Trusty Steed"->"Верный конь";case "Thoroughbred"->"Породистый конь";case "War Horse"->"Боевая лошадь";case "Mule"->"Мул";case "Saddle"->"Седло";case "Name Tag"->"Бирка";case "Iron Horse Armor"->"Железная броня";case "Gold Horse Armor"->"Золотая броня";case "Diamond Horse Armor"->"Алмазная броня";default->name;};
            entries.add(new Offer(entries.size(),group,item,horse,(long)cost,name));
        }
        horses=Map.copyOf(animals);var copy=new HashMap<Integer,List<Offer>>();offers.forEach((key,value)->copy.put(key,List.copyOf(value)));groups=Map.copyOf(copy);
    }
    private static int integer(Map<?,?> map,String key){return Integer.parseInt(Objects.toString(map.get(key)));}
    private static double number(Map<?,?> map,String key){double value=Double.parseDouble(Objects.toString(map.get(key)));if(!Double.isFinite(value))throw new IllegalArgumentException("Неверное число в настройках конюшни");return value;}
    public Horse horse(int id){var horse=horses.get(id);if(horse==null)throw new IllegalArgumentException("Неизвестная лошадь.");return horse;}
    public List<Offer> group(int id){return groups.getOrDefault(id,List.of());}
    public Offer offer(int group,int selection){var entries=group(group);if(entries.isEmpty())throw new IllegalArgumentException("В этом отделе конюшни нет товаров.");return entries.get(Math.floorMod(selection,entries.size()));}
}
