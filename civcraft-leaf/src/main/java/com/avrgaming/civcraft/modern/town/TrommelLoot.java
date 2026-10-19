package com.avrgaming.civcraft.modern.town;

import java.io.File;
import java.util.*;
import java.util.random.RandomGenerator;
import org.bukkit.configuration.file.YamlConfiguration;

/** Original ordered thresholds, including unreachable branches in the shipped configuration. */
public final class TrommelLoot {
    private record Rule(String key,String item) {}
    private record Table(int maximum,List<Rule> rules,Map<String,Double> rates) {}
    private final Map<String,Table> tables;
    private static final List<Rule> COBBLE=List.of(new Rule("chromium_chance","mat_chromium_ore"),new Rule("emerald_chance","EMERALD"),new Rule("diamond_chance","DIAMOND"),new Rule("gold_chance","GOLD_INGOT"),new Rule("redstone_chance","REDSTONE"),new Rule("iron_chance","IRON_INGOT"));
    private static final List<Rule> STONE=List.of(new Rule("crystal_chance","CRYSTAL"),new Rule("refined_chromium_chance","mat_forged_chromium"),new Rule("refined_tungsten_chance","mat_forged_tungsten"),new Rule("crystal_fragment_chance","FRAGMENT"),new Rule("chromium_chance","mat_chromium_ore"),new Rule("emerald_chance","EMERALD"),new Rule("diamond_chance","DIAMOND"),new Rule("tungsten_chance","mat_tungsten_ore"),new Rule("gold_chance","GOLD_INGOT"),new Rule("redstone_chance","REDSTONE"),new Rule("iron_chance","IRON_INGOT"),new Rule("polished_rate","POLISHED"),new Rule("dirt_rate","DIRT"));
    public TrommelLoot(File file) {
        var config=YamlConfiguration.loadConfiguration(file);var result=new HashMap<String,Table>();
        for(String material:List.of("COBBLESTONE","GRANITE","DIORITE","ANDESITE")) {
            String prefix="trommel_"+(material.equals("COBBLESTONE")?"gravel":material.toLowerCase(Locale.ROOT));
            int max=config.getInt(prefix+".max");if(max<1)throw new IllegalArgumentException("Неверный диапазон случайного выбора промывателя");
            var rules=material.equals("COBBLESTONE")?COBBLE:STONE;var rates=new HashMap<String,Double>();
            for(var rule:rules){double rate=config.getDouble(prefix+"."+rule.key());if(!Double.isFinite(rate) || rate<0 || rate>1)throw new IllegalArgumentException("Неверный шанс промывателя: "+prefix+"."+rule.key());rates.put(rule.key(),rate);}
            result.put(material,new Table(max,rules,Map.copyOf(rates)));
        }
        tables=Map.copyOf(result);
    }
    public static boolean accepts(String material,int level) {
        if(level<1 || level>4)throw new IllegalArgumentException("Неверный уровень промывателя");
        return switch(material){case "COBBLESTONE"->true;case "GRANITE"->level>=2;case "DIORITE"->level>=3;case "ANDESITE"->level>=4;default->false;};
    }
    public int maximum(String material){return table(material).maximum();}
    private Table table(String material){var table=tables.get(material);if(table==null)throw new IllegalArgumentException("Неподдерживаемый материал промывателя: "+material);return table;}
    public String select(String material,double extraction,int roll) {
        var table=table(material);
        if(!Double.isFinite(extraction) || extraction<0 || extraction>1000 || roll<0 || roll>=table.maximum())throw new IllegalArgumentException("Неверный результат случайного выбора промывателя");
        for(var rule:table.rules()) {
            double chance=table.rates().get(rule.key());chance+=chance*extraction;
            if(roll<(int)(chance*table.maximum()))return rule.item().equals("POLISHED")?"POLISHED_"+material:rule.item();
        }
        return "GRAVEL";
    }
    public static String crystal(boolean fragment,int maximum,int second,int third) {
        if(maximum<1 || second<0 || second>=maximum || third<0 || third>=maximum)throw new IllegalArgumentException("Неверный результат случайного выбора кристалла");
        boolean tier2=second<maximum/10;
        boolean ionic=tier2?third<maximum/2:second<maximum/2;
        return "mat_"+(ionic?"ionic":"metallic")+"_crystal_"+(fragment?"fragment_":"")+(tier2?2:1);
    }
    public String roll(String material,double extraction,RandomGenerator random) {
        int max=maximum(material);String result=select(material,extraction,random.nextInt(max));
        if(result.equals("CRYSTAL") || result.equals("FRAGMENT")){int second=random.nextInt(max);return crystal(result.equals("FRAGMENT"),max,second,second<max/10?random.nextInt(max):0);}
        return result;
    }
    public Set<String> items() {
        var result=new HashSet<String>();for(var table:tables.values())for(var rule:table.rules())if(rule.item().startsWith("mat_"))result.add(rule.item());
        for(boolean fragment:List.of(false,true))for(int second:List.of(0,10000,50000))for(int third:List.of(0,50000))result.add(crystal(fragment,100000,second,third));return Set.copyOf(result);
    }
}
