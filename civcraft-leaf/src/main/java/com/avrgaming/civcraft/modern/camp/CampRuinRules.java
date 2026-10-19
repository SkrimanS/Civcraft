package com.avrgaming.civcraft.modern.camp;

import java.util.*;
import java.util.random.RandomGenerator;
import org.bukkit.Material;

/** Inclusive comparisons and ordered rolls from Camp.fancyCampBlockDestory. */
public final class CampRuinRules {
    private CampRuinRules() {}
    private static final Set<Material> ALWAYS=Set.of(Material.BEDROCK,Material.COAL_BLOCK,Material.EMERALD_BLOCK,Material.LAPIS_BLOCK,Material.SPONGE,Material.WET_SPONGE,Material.HAY_BLOCK,Material.GOLD_BLOCK,Material.DIAMOND_BLOCK,Material.IRON_BLOCK,Material.REDSTONE_BLOCK,Material.ENDER_CHEST,Material.BEACON);
    public record Result(Material replacement,boolean effect) {}
    public static Result roll(Material material,boolean preserved,RandomGenerator random) {
        if(preserved || material==Material.CHEST || material==Material.TRAPPED_CHEST)return new Result(null,false);
        if(ALWAYS.contains(material) || random.nextInt(100)<=10)return new Result(Material.GRAVEL,false);
        if(random.nextInt(100)<=50)return new Result(Material.FIRE,false);
        return new Result(null,random.nextInt(100)<=1);
    }
}
