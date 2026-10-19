package com.avrgaming.civcraft.modern.culture;

import java.util.*;

/** Original culture.yml yields, with explicit equivalents for the modern biome registry. */
public final class BiomePotential {
    public record Yield(double coins,double hammers,double growth,double happiness,double beakers) {
        public Yield add(Yield other){return new Yield(coins+other.coins,hammers+other.hammers,growth+other.growth,happiness+other.happiness,beakers+other.beakers);}
    }
    private final Map<String,Yield> yields;
    public BiomePotential(List<? extends Map<?,?>> entries) {
        Map<String,Yield> result=new HashMap<>();
        for(var entry:entries) {
            String name=String.valueOf(entry.get("name")).toUpperCase(Locale.ROOT);
            result.put(name,new Yield(number(entry,"coins"),number(entry,"hammers"),number(entry,"growth"),number(entry,"happiness"),number(entry,"beakers")));
        }
        if(!result.containsKey("UNKNOWN"))throw new IllegalArgumentException("Не настроен биом культуры UNKNOWN");
        yields=Map.copyOf(result);
    }
    private static double number(Map<?,?> entry,String field) {
        Object value=entry.get(field);double number=value instanceof Number n?n.doubleValue():Double.parseDouble(String.valueOf(value));
        if(!Double.isFinite(number) || number<0)throw new IllegalArgumentException("Неверный потенциал биома: "+field);
        return number;
    }
    public Yield yield(String biome){return yields.getOrDefault(legacy(biome),yields.get("UNKNOWN"));}
    public String legacy(String biome) {
        String name=biome.toUpperCase(Locale.ROOT).replace("MINECRAFT:","");
        if(yields.containsKey(name))return name;
        return switch(name) {
            case "SUNFLOWER_PLAINS"->"MUTATED_PLAINS";
            case "FLOWER_FOREST","CHERRY_GROVE"->"MUTATED_FOREST";
            case "OLD_GROWTH_BIRCH_FOREST"->"MUTATED_BIRCH_FOREST";
            case "DARK_FOREST","PALE_GARDEN"->"ROOFED_FOREST";
            case "OLD_GROWTH_PINE_TAIGA"->"REDWOOD_TAIGA";
            case "OLD_GROWTH_SPRUCE_TAIGA"->"MUTATED_REDWOOD_TAIGA";
            case "SNOWY_TAIGA"->"TAIGA_COLD";
            case "SNOWY_PLAINS","SNOWY_SLOPES"->"ICE_FLATS";
            case "ICE_SPIKES"->"MUTATED_ICE_FLATS";
            case "SWAMP","MANGROVE_SWAMP"->"SWAMPLAND";
            case "WINDSWEPT_HILLS","WINDSWEPT_GRAVELLY_HILLS"->"EXTREME_HILLS";
            case "WINDSWEPT_FOREST"->"EXTREME_HILLS_WITH_TREES";
            case "WINDSWEPT_SAVANNA"->"MUTATED_SAVANNA";
            case "SAVANNA_PLATEAU"->"SAVANNA_ROCK";
            case "BADLANDS"->"MESA";
            case "ERODED_BADLANDS"->"MUTATED_MESA";
            case "WOODED_BADLANDS"->"MESA_ROCK";
            case "SPARSE_JUNGLE","BAMBOO_JUNGLE"->"JUNGLE_EDGE";
            case "MUSHROOM_FIELDS"->"MUSHROOM_ISLAND";
            case "BEACH"->"BEACHES";
            case "STONY_SHORE"->"STONE_BEACH";
            case "SNOWY_BEACH"->"COLD_BEACH";
            case "WARM_OCEAN","LUKEWARM_OCEAN","COLD_OCEAN"->"OCEAN";
            case "DEEP_LUKEWARM_OCEAN","DEEP_COLD_OCEAN","DEEP_FROZEN_OCEAN"->"DEEP_OCEAN";
            case "MEADOW"->"PLAINS";
            case "GROVE"->"TAIGA_COLD";
            case "JAGGED_PEAKS","FROZEN_PEAKS","STONY_PEAKS"->"ICE_MOUNTAINS";
            default->"UNKNOWN";
        };
    }
}
