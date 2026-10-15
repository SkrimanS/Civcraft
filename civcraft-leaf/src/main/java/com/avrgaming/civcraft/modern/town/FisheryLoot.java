package com.avrgaming.civcraft.modern.town;

import java.io.File;
import java.util.*;
import java.util.random.RandomGenerator;
import org.bukkit.configuration.file.YamlConfiguration;

/** FisheryAsyncTask probabilities: nested thresholds, not independent rolls. */
public final class FisheryLoot {
    private final int maximum;
    private final double[] rates;
    public FisheryLoot(File file) {
        var config=YamlConfiguration.loadConfiguration(file);maximum=config.getInt("fishery.tierMax");
        if(maximum<1)throw new IllegalArgumentException("Invalid fishery random range");
        rates=new double[5];for(int i=0;i<5;i++){rates[i]=config.getDouble("fishery.t"+i+"_rate");if(!Double.isFinite(rates[i]) || rates[i]<0 || rates[i]>1)throw new IllegalArgumentException("Invalid fishery tier rate");}
    }
    public int tier(int level,int roll) {
        if(level<1 || level>4 || roll<0 || roll>=maximum)throw new IllegalArgumentException("Invalid fishery roll");
        for(int tier=4;tier>=1;tier--)if((tier==1 || level>=tier) && roll<(int)(rates[tier]*maximum))return tier;
        return 0;
    }
    public static int group(String biome) {
        return switch(biome.toUpperCase(Locale.ROOT)) {
            case "BIRCH_FOREST_HILLS","MUTATED_BIRCH_FOREST","MUTATED_BIRCH_FOREST_HILLS","MUTATED_TAIGA_COLD","MUTATED_EXTREME_HILLS","MUTATED_EXTREME_HILLS_WITH_TREES","ICE_MOUNTAINS","MUTATED_JUNGLE_EDGE","JUNGLE_HILLS","MUTATED_JUNGLE","MUTATED_MESA","MUTATED_MESA_CLEAR_ROCK","MUTATED_MESA_ROCK","MUTATED_SAVANNA","MUTATED_SAVANNA_ROCK","SMALLER_EXTREME_HILLS","MUTATED_SWAMPLAND","MUTATED_TAIGA"->1;
            case "BIRCH_FOREST","EXTREME_HILLS","FOREST","FOREST_HILLS","ICE_FLATS","MUTATED_ICE_FLATS","JUNGLE","JUNGLE_EDGE","MUTATED_REDWOOD_TAIGA","MUTATED_REDWOOD_TAIGA_HILLS","REDWOOD_TAIGA","REDWOOD_TAIGA_HILLS","ROOFED_FOREST","MESA","MESA_CLEAR_ROCK","MESA_ROCK","EXTREME_HILLS_WITH_TREES","SAVANNA","SAVANNA_ROCK","TAIGA","TAIGA_HILLS"->2;
            case "BEACHES","COLD_BEACH","TAIGA_COLD","DEEP_OCEAN","DESERT","DESERT_HILLS","MUTATED_DESERT","FROZEN_OCEAN","FROZEN_RIVER","MUSHROOM_ISLAND","MUSHROOM_ISLAND_SHORE","OCEAN","PLAINS","RIVER","STONE_BEACH","SWAMPLAND"->3;
            default->0;
        };
    }
    private static final String[][] FISH={
        {"brown_trout","gag_grouper","atlantic_striped_bass","arrowtooth_flounder","atlantic_cod","atlantic_surfclam"},
        {"brook_trout","red_grouper","pacific_ocean_perch","summer_flounder","pacific_cod","ocean_quahog"},
        {"cutthroat_trout","black_sea_bass","acadian_redfish","winter_flounder","lingcod","northern_quahog"},
        {"rainbow_trout","wreckfish","widow_rockfish","yellowtail_flounder","sablefish","geoduck"}
    };
    public static String item(int tier,int group,int fishRoll,int junkRoll) {
        if(tier<0 || tier>4 || group<0 || group>3 || fishRoll<0 || fishRoll>=100 || junkRoll<0 || junkRoll>=100)throw new IllegalArgumentException("Invalid fishery selection");
        if(tier==0) {
            if(fishRoll>=95)return "mat_fish_pufferfish";
            if(fishRoll>85)return "mat_fish_clownfish";
            if(fishRoll>75)return "mat_fish_salmon";
            if(fishRoll>50)return "mat_fish_fish";
            return junkRoll>90?"mat_minnows":junkRoll>70?"mat_tadpole":junkRoll>50?"mat_seaweed":junkRoll>30?"mat_tangled_string":"mat_pond_scum";
        }
        if(group==0)return tier==1?"COD":"mat_fish_fish";
        return "mat_fish_"+FISH[tier-1][(group-1)*2+(fishRoll<(tier<=2?90:80)?0:1)];
    }
    public String roll(int level,String legacyBiome,RandomGenerator random) {
        int tier=tier(level,random.nextInt(maximum)),fish=random.nextInt(100);
        return item(tier,group(legacyBiome),fish,tier==0&&fish<=50?random.nextInt(100):0);
    }
    public static int damageAfterUse(int damage) {
        if(damage<0)throw new IllegalArgumentException("Invalid fishing rod damage");
        return damage>=63?-1:damage+1;
    }
}
