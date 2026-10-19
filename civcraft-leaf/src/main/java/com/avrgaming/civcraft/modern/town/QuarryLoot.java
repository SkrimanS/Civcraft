package com.avrgaming.civcraft.modern.town;

import java.io.File;
import java.util.*;
import java.util.random.RandomGenerator;
import org.bukkit.configuration.file.YamlConfiguration;

/** QuarryAsyncTask tool gates, ordered thresholds and Efficiency output/durability multiplier. */
public final class QuarryLoot {
    public record Reward(String item,int amount) {}
    private final int maximum;
    private final double despotism,penalty;
    private final Map<String,Double> chances;
    public QuarryLoot(File file) {
        var config=YamlConfiguration.loadConfiguration(file);maximum=config.getInt("quarry.max");
        if(maximum<1)throw new IllegalArgumentException("Неверный диапазон случайного выбора карьера");
        despotism=config.getDouble("quarry.despotism_rate");penalty=config.getDouble("quarry.penalty_rate");
        if(!Double.isFinite(despotism) || despotism<0 || !Double.isFinite(penalty) || penalty<0)throw new IllegalArgumentException("Неверные коэффициенты правительства для карьера");
        var rates=new HashMap<String,Double>();
        for(String key:List.of("rare_chance","tungsten_chance","gold_chance","redstone_chance","iron_chance","coal_rate","other_rate","cobblestone_rate")) {
            double rate=config.getDouble("quarry."+key);if(!Double.isFinite(rate) || rate<0 || rate>1)throw new IllegalArgumentException("Неверный коэффициент карьера: "+key);rates.put(key,rate);
        }
        chances=Map.copyOf(rates);
    }
    public int maximum(){return maximum;}
    public static boolean accepts(String tool,int level) {
        if(level<1 || level>4)throw new IllegalArgumentException("Неверный уровень карьера");
        return switch(tool){case "WOODEN_PICKAXE","GOLDEN_PICKAXE"->true;case "STONE_PICKAXE"->level>=2;case "IRON_PICKAXE"->level>=3;case "DIAMOND_PICKAXE"->level>=4;default->false;};
    }
    public static int modifier(int efficiency){if(efficiency<0 || efficiency>255)throw new IllegalArgumentException("Неверный уровень Эффективности");return efficiency+1;}
    public static int damageAfterUse(String tool,int damage,int modifier,int amount) {
        int maximum=switch(tool){case "WOODEN_PICKAXE"->59;case "STONE_PICKAXE"->131;case "IRON_PICKAXE"->250;case "GOLDEN_PICKAXE"->32;case "DIAMOND_PICKAXE"->1561;default->throw new IllegalArgumentException("Неподдерживаемый инструмент карьера");};
        if(damage<0 || modifier<1 || amount<1)throw new IllegalArgumentException("Неверное состояние инструмента карьера");
        long after=(long)damage+modifier;return amount==1 && after<maximum?(int)after:-1;
    }
    private double chance(String key,double extraction,String government) {
        double value=chances.get(key);value+=value*extraction;
        if("gov_despotism".equals(government))value*=despotism;
        else if("gov_theocracy".equals(government) || "gov_monarchy".equals(government))value*=penalty;
        return value;
    }
    public String select(String tool,double extraction,String government,int roll) {
        if(!accepts(tool,4) || !Double.isFinite(extraction) || extraction<0 || extraction>1000 || roll<0 || roll>=maximum)throw new IllegalArgumentException("Неверный результат случайного выбора карьера");
        boolean wood=tool.equals("WOODEN_PICKAXE"),gold=tool.equals("GOLDEN_PICKAXE"),stone=tool.equals("STONE_PICKAXE");
        if(!wood && !gold) {
            if(!stone) {
                if(roll<(int)(chance("rare_chance",extraction,government)*maximum))return "RARE";
                if(roll<(int)(chance("tungsten_chance",extraction,government)*maximum))return "mat_tungsten_ore";
            }
            if(roll<(int)(chance("gold_chance",extraction,government)*maximum))return "GOLD_INGOT";
            if(!stone && roll<(int)(chance("redstone_chance",extraction,government)*maximum))return "REDSTONE";
            if(roll<(int)(chance("iron_chance",extraction,government)*maximum))return "IRON_INGOT";
        }
        double divisor=wood || gold?2:1;
        if(roll<(int)((chance("coal_rate",extraction,government)/divisor)*maximum))return "COAL";
        if(roll<(int)((chance("other_rate",extraction,government)/divisor)*maximum))return "OTHER";
        if(gold || roll<(int)((chance("cobblestone_rate",extraction,government)/2)*maximum))return "COBBLESTONE";
        return "JUNK";
    }
    public String detail(String result,int roll) {
        if(result.equals("JUNK")){if(roll<0 || roll>=10)throw new IllegalArgumentException("Неверный результат выбора отходов карьера");return roll<2?"PODZOL":roll<5?"COARSE_DIRT":"DIRT";}
        if(roll<0 || roll>=maximum)throw new IllegalArgumentException("Неверный результат дополнительного выбора карьера");
        return switch(result){case "OTHER"->roll<maximum/8?"ANDESITE":roll<maximum/5?"DIORITE":"GRANITE";case "RARE"->roll<maximum/5?"EMERALD":"DIAMOND";default->result;};
    }
    public Reward roll(String tool,int efficiency,double extraction,String government,RandomGenerator random) {
        int multiplier=modifier(efficiency);String item=select(tool,extraction,government,random.nextInt(maximum));
        int amount=item.equals("REDSTONE")?Math.multiplyExact(random.nextInt(5)+1,multiplier):multiplier;
        if(item.equals("JUNK") || item.equals("OTHER") || item.equals("RARE"))item=detail(item,random.nextInt(item.equals("JUNK")?10:maximum));
        return new Reward(item,amount);
    }
}
